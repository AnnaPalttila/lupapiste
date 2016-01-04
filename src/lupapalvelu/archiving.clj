(ns lupapalvelu.archiving
  (:require [sade.http :as http]
            [sade.env :as env]
            [cheshire.core :as json]
            [lupapiste-commons.tos-metadata-schema :as tms]
            [schema.core :as s]
            [lupapalvelu.tiedonohjaus :as tiedonohjaus]
            [lupapalvelu.pdf.pdf-export :as pdf-export]
            [clojure.java.io :as io]
            [lupapalvelu.attachment]
            [ring.util.codec :as codec])
  (:import (java.text SimpleDateFormat)
           (java.util Date)))

(defn- build-url [id]
  (let [host (env/value :arkisto :host)
        app-id (env/value :arkisto :app-id)
        app-key (env/value :arkisto :app-key)
        encoded-id (codec/url-encode id)]
    (str host "/documents/" encoded-id "?app-id=" app-id "&app-key=" app-key)))

(defn- upload-file [id is-or-file content-type metadata]
  (http/put (build-url id) {:multipart
                                         [{:name "metadata"
                                           :mime-type "application/json"
                                           :encoding "UTF-8"
                                           :content (json/generate-string metadata)}
                                          {:name "file"
                                           :content is-or-file
                                           :mime-type content-type}]}))

(defn- find-op [{:keys [primaryOperation secondaryOperations]} op-id]
  (if (= op-id (:id primaryOperation))
    (:name primaryOperation)
    (first (filter #(= op-id (:id %)) secondaryOperations))))

(defn- ->iso-8601-date [date]
  (let [format (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ssXXX")]
    (.format format date)))

(defn- get-verdict-date [{:keys [verdicts]} type]
  (let [ts (->> verdicts
                (map (fn [{:keys [paatokset]}]
                       (->> (map #(get-in % [:paivamaarat type]) paatokset)
                            (remove nil?)
                            (first))))
                (remove nil?)
                (first))]
    (when ts
      (->iso-8601-date (Date. ^Long ts)))))

(defn- get-from-verdict-minutes [{:keys [verdicts]} key]
  (->> verdicts
       (map (fn [{:keys [paatokset]}]
              (map (fn [pt] (map key (:poytakirjat pt))) paatokset)))
       (flatten)
       (remove nil?)
       (first)))

(defn- get-usages [{:keys [documents]} op-id]
  (let [op-docs (remove #(nil? (get-in % [:schema-info :op :id])) documents)
        id-to-usage (into {} (map (fn [d] {(get-in d [:schema-info :op :id])
                                           (get-in d [:data :kaytto :kayttotarkoitus :value])}) op-docs))]
    (if op-id
      [(get id-to-usage op-id)]
      (vals id-to-usage))))

(defn- make-version-number [{{{:keys [major minor]} :version} :latestVersion}]
  (str major "." minor))

(defn- generate-archive-metadata [application user & [attachment]]
  (cond-> {:type                (if attachment (:type attachment) :application)
           :applicationId       (:id application)
           :buildingIds         (remove nil? (map :buildingId (:buildings application)))
           :nationalBuildingIds (remove nil? (map :nationalId (:buildings application)))
           :propertyId          (:propertyId application)
           :applicant           (:applicant application)
           :operations          (if (:op attachment)
                                  (find-op application (get-in attachment [:op :id]))
                                  (concat [(get-in application [:primaryOperation :name])] (map :name (:secondaryOperations application))))
           :tosFunction         (first (filter #(= (:tosFunction application) (:code %)) (tiedonohjaus/available-tos-functions (:organization application))))
           :address             (:address application)
           :organization        (:organization application)
           :municipality        (:municipality application)
           :location            (:location application)
           :kuntalupatunnukset  (map :kuntalupatunnus (:verdicts application))
           :lupapvm             (get-verdict-date application :lainvoimainen)
           :paatospvm           (get-verdict-date application :anto)
           :paatoksentekija     (get-from-verdict-minutes application :paatoksentekija)
           :tiedostonimi        (get-in attachment [:latestVersion :filename] (str (:id application) ".pdf"))
           :kasittelija         (select-keys (:authority application) [:username :firstName :lastName])
           :arkistoija          (select-keys user [:username :firstName :lastName])
           :kayttotarkoitukset  (if (:op attachment)
                                  (get-usages application (get-in attachment [:op :id]))
                                  (get-usages application nil))
           :kieli               "fi"
           :versio              (if attachment (make-version-number attachment) "1.0")}
          (:contents attachment) (conj {:contents (:contents attachment)})
          (:size attachment)     (conj {:size (:size attachment)})
          (:scale attachment)    (conj {:scale (:scale attachment)})
          true                   (merge (or (:metadata attachment) (:metadata application)))))

(defn send-to-archive [{:keys [attachments id] :as application} attachment-ids user archive-application?]
  (let [selected-attachments (filter (fn [{:keys [id latestVersion metadata]}]
                                       (and (attachment-ids id) (:archivable latestVersion) (seq metadata)))
                                     attachments)]
    (when archive-application?
      (let [application-file (pdf-export/generate-pdf-a-application-to-file application :fi)
            metadata (generate-archive-metadata application user)]
        (upload-file id application-file "application/pdf" metadata)
        (io/delete-file application-file :silently)))
    (doseq [attachment selected-attachments]
      (let [{:keys [content content-type]} (lupapalvelu.attachment/get-attachment-file (get-in attachment [:latestVersion :fileId]))
            metadata (generate-archive-metadata application user attachment)]
        (upload-file (:id attachment) (content) content-type metadata)))))
