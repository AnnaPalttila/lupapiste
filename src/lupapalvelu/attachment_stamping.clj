(ns lupapalvelu.attachment-stamping
  (:require [clojure.java.io :as io]
            [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn warnf error errorf fatal]]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.stamper :as stamper]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.pdf.pdfa-conversion :as pdf-conversion]
            [lupapalvelu.tiedonohjaus :as tos]
            [lupapalvelu.job :as job]
            [lupapalvelu.i18n :as i18n]
            [sade.util :refer [future* fn-> fn->>] :as util]
            [sade.strings :as ss])
  (:import [java.io File]))

(defn status [job-id version timeout]
  (job/status job-id (util/->long version) (util/->long timeout)))

(defn- stampable? [attachment]
  (let [latest       (-> attachment :versions last)
        content-type (:contentType latest)
        stamped      (:stamped latest)]
    (and (not stamped) (or (= "application/pdf" content-type) (ss/starts-with content-type "image/")))))

(defn- ->file-info [attachment]
  (let [versions   (-> attachment :versions reverse)
        re-stamp?  (:stamped (first versions))
        source     (if re-stamp? (second versions) (first versions))]
    (assoc (select-keys source [:contentType :fileId :filename :size :archivable])
           :re-stamp? re-stamp?
           :attachment-id (:id attachment)
           :attachment-type (:type attachment))))

(defn- stamp-attachment! [stamp file-info {:keys [application user now] :as context}]
  (let [{:keys [attachment-id contentType fileId filename re-stamp?]} file-info
        options (select-keys context [:x-margin :y-margin :transparency :page])
        file (File/createTempFile "lupapiste.stamp." ".tmp")
        new-file-id (mongo/create-id)]
    (with-open [out (io/output-stream file)]
      (stamper/stamp stamp fileId out options))
    (let [is-pdf-a? (pdf-conversion/ensure-pdf-a-by-organization file (:organization application))]
      (debug "uploading stamped file: " (.getAbsolutePath file))
      (mongo/upload new-file-id filename contentType file :application (:id application))
      (if re-stamp? ; FIXME these functions should return updates, that could be merged into comment update
        (attachment/update-latest-version-content! user application attachment-id new-file-id (.length file) now)
        (attachment/set-attachment-version! application
                                           (attachment/get-attachment-info application attachment-id)
                                           {:attachment-id  attachment-id
                                            :file-id new-file-id :original-file-id new-file-id
                                            :filename filename
                                            :content-type contentType :size (.length file)
                                            :comment-text nil :now now :user user
                                            :archivable is-pdf-a?
                                            :archivabilityError (when-not is-pdf-a? :invalid-pdfa)
                                            :stamped true :comment? false :state :ok}))
      (io/delete-file file :silently)
      (tos/mark-attachment-final! application now attachment-id))
    new-file-id))

(defn- asemapiirros? [{{type :type-id} :attachment-type}]
  (= :asemapiirros (keyword type)))

(defn- building->str [lang {:keys [short-id national-id]}]
  (when-not (or (ss/blank? short-id) (ss/blank? national-id))
    (i18n/with-lang lang
      (str (i18n/loc "stamp.building") " " short-id " : " national-id))))

(defn- info-fields->stamp [{:keys [text created transparency lang]} fields]
  {:pre [text (pos? created)]}
  (->> (update fields :buildings (fn->> (map (partial building->str lang)) sort))
       ((juxt :backend-id :section :extra-info :buildings :organization))
       flatten
       (map (fn-> str (ss/limit 100)))
       (stamper/make-stamp (ss/limit text 100) created transparency)))

(defn- stamp-attachments!
  [file-infos {:keys [job-id application info-fields] :as context}]
  (let [stamp-without-buildings (info-fields->stamp context (dissoc info-fields :buildings))
        stamp-with-buildings (info-fields->stamp context info-fields)]
    (doseq [file-info file-infos]
      (try
        (debug "Stamping" (select-keys file-info [:attachment-id :contentType :fileId :filename :re-stamp?]))
        (job/update job-id assoc (:attachment-id file-info) {:status :working :fileId (:fileId file-info)})
        (let [stamp (if (asemapiirros? file-info) stamp-with-buildings stamp-without-buildings)
              new-file-id (stamp-attachment! stamp file-info context)]
          (job/update job-id assoc (:attachment-id file-info) {:status :done :fileId new-file-id}))
        (catch Throwable t
          (errorf t "failed to stamp attachment: application=%s, file=%s" (:id application) (:fileId file-info))
          (job/update job-id assoc (:attachment-id file-info) {:status :error :fileId (:fileId file-info)}))))))

(defn- stamp-job-status [data]
  (if (every? #{:done :error} (map #(get-in % [:status]) (vals data))) :done :running))

(defn make-stamp-job [attachment-infos context]
  (let [file-infos (map ->file-info attachment-infos)
        job (-> (zipmap (map :attachment-id file-infos) (map #(assoc % :status :pending) file-infos))
                (job/start stamp-job-status))]
    (future* (stamp-attachments! file-infos (assoc context :job-id (:id job))))
    job))
