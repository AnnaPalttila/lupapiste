(ns lupapalvelu.xml.asianhallinta.verdict
  (:require [sade.core :refer [ok fail fail!] :as core]
            [sade.xml :as xml]
            [pandect.core :as pandect]
            [taoensso.timbre :refer [error]]
            [me.raynes.fs :as fs]
            [me.raynes.fs.compression :as fsc]
            [monger.operators :refer :all]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.organization :as org]
            [lupapalvelu.action :as action]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.user :as user]
            [lupapalvelu.notifications :as notifications]
            [clojure.string :as s]
            [clojure.java.io :as io]
            [sade.common-reader :as cr]
            [lupapalvelu.attachment :as attachment])
  (:import (java.util.zip ZipFile)))

; Patched from me.raynes.fs.compression
(defn unzip
  "Takes the path to a zipfile source and unzips it to target-dir."
  ([source]
    (unzip source (name source)))
  ([source target-dir]
    (with-open [zip (ZipFile. (fs/file source))]
      (let [entries (enumeration-seq (.entries zip))
            target-file #(fs/file target-dir (str %))]
        (doseq [entry entries :when (not (.isDirectory ^java.util.zip.ZipEntry entry))
                :let [f (target-file entry)]]
          (fs/mkdirs (fs/parent f))
          (io/copy (.getInputStream zip entry) f))))
    target-dir))

(defn- error-and-fail! [error-msg fail-key]
  (error error-msg)
  (fail! fail-key))

(defn- unzip-file [path-to-zip]
  (if-not (fs/exists? path-to-zip)
    (error-and-fail! (str "Could not find file " path-to-zip) :error.integration.asianhallinta-file-not-found)
    (let [tmp-dir (fs/temp-dir "ah")]
      (unzip path-to-zip tmp-dir)
      tmp-dir)))

(defn- ensure-attachments-present! [unzipped-path attachments]
  (let [attachment-paths (->> attachments
                              (map :LinkkiLiitteeseen)
                              (map fs/base-name))]
    (doseq [filename attachment-paths]
      (when (empty? (fs/find-files unzipped-path (re-pattern filename)))
        (error-and-fail! (str "Attachment referenced in XML was not present in zip: " filename) :error.integration.asianhallinta-missing-attachment)))))

(defn- build-verdict [{:keys [AsianPaatos]}]
  {:id              (mongo/create-id)
   :kuntalupatunnus (:AsianTunnus AsianPaatos)
   :timestamp (core/now)
   :source    "ah"
   :paatokset [{:paatostunnus (:PaatoksenTunnus AsianPaatos)
                :paivamaarat  {:anto (cr/to-timestamp (:PaatoksenPvm AsianPaatos))}
                :poytakirjat  [{:paatoksentekija (:PaatoksenTekija AsianPaatos)
                                :paatospvm       (cr/to-timestamp (:PaatoksenPvm AsianPaatos))
                                :pykala          (:Pykala AsianPaatos)
                                :paatoskoodi     (:PaatosKoodi AsianPaatos)
                                :id              (mongo/create-id)}]}]})

(defn- insert-attachment! [application attachment unzipped-path verdict-id poytakirja-id]
  (let [filename      (fs/base-name (:LinkkiLiitteeseen attachment))
        file          (fs/file (s/join "/" [unzipped-path filename]))
        file-size     (.length file)
        orgs          (org/resolve-organizations
                        (:municipality application)
                        (:permitType application))
        batchrun-user (user/batchrun-user (map :id orgs))
        target        {:type "verdict" :id verdict-id :poytakirjaId poytakirja-id}
        attachment-id (pandect/sha1 (:LinkkiLiitteeseen attachment))]
    (attachment/attach-file! {:application application
                              :filename filename
                              :size file-size
                              :content file
                              :attachment-id attachment-id
                              :attachment-type {:type-group "muut" :type-id "muu"}
                              :target target
                              :required false
                              :locked true
                              :user batchrun-user
                              :created (core/now)
                              :state :ok})))

(defn process-ah-verdict [path-to-zip ftp-user]
  (try
    (let [unzipped-path (unzip-file path-to-zip)
          xmls (fs/find-files unzipped-path #".*xml$")]
      ; path must contain exactly one xml
      (when-not (= (count xmls) 1)
        (error-and-fail! (str "Expected to find one xml, found " (count xmls)) :error.integration.asianhallinta-wrong-number-of-xmls))

      ; parse XML
      (let [parsed-xml (-> (first xmls) slurp xml/parse cr/strip-xml-namespaces xml/xml->edn)
            attachments (-> (get-in parsed-xml [:AsianPaatos :Liitteet])
                            (cr/ensure-sequential :Liite)
                            :Liite)]
        ; Check that all referenced attachments were included in zip
        (ensure-attachments-present! unzipped-path attachments)

        ; Create verdict
        ; -> fetch application
        (let [application-id (get-in parsed-xml [:AsianPaatos :HakemusTunnus])
              application (domain/get-application-no-access-checking application-id)
              org-scope (org/resolve-organization-scope (:municipality application) (:permitType application))]

          ; -> check ftp-user has right to modify app
          (when-not (= ftp-user (get-in org-scope [:caseManagement :ftpUser]))
            (error-and-fail! (str "FTP user " ftp-user " is not allowed to make changes to application " application-id) :error.integration.asianhallinta.unauthorized))

          ; -> check that application is in correct state
          (when-not (#{:constructionStarted :sent :verdictGiven} (keyword (:state application)))
            (error-and-fail!
              (str "Application " application-id " in wrong state (" (:state application) ") for asianhallinta verdict") :error.integration.asianhallinta.wrong-state))

          ; -> build update clause
          ; -> update-application
          (let [new-verdict   (build-verdict parsed-xml)
                command       (action/application->command application)
                poytakirja-id (get-in new-verdict [:paatokset 0 :poytakirjat 0 :id])
                update-clause {$push {:verdicts new-verdict}
                               $set  (merge
                                       {:modified (core/now)}
                                       (when (#{:sent} (keyword (:state application)))
                                         {:state :verdictGiven}))}]

            (action/update-application command update-clause)
            (doseq [attachment attachments]
              (insert-attachment!
                application
                attachment
                unzipped-path
                (:id new-verdict)
                poytakirja-id))
            (notifications/notify! :application-verdict command)
            (ok)))))
    (catch Exception e
      (if-let [error-key (some-> e ex-data :object :text)]
        (fail error-key)
        (fail :error.unknown)))))
