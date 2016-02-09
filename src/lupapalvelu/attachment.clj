(ns lupapalvelu.attachment
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn warnf error errorf fatal]]
            [clojure.java.io :as io]
            [clojure.set :refer [rename-keys]]
            [monger.operators :refer :all]
            [schema.core :as sc]
            [sade.schemas :as ssc]
            [sade.util :as util]
            [sade.env :as env]
            [sade.strings :as ss]
            [sade.core :refer :all]
            [sade.http :as http]
            [lupapalvelu.action :refer [update-application application->command]]
            [lupapalvelu.attachment-accessibility :as access]
            [lupapalvelu.attachment-metadata :as metadata]
            [lupapalvelu.domain :refer [get-application-as get-application-no-access-checking]]
            [lupapalvelu.states :as states]
            [lupapalvelu.comment :as comment]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.user :as user]
            [lupapalvelu.mime :as mime]
            [lupapalvelu.pdf.pdf-export :as pdf-export]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.tiedonohjaus :as tos]
            [lupapalvelu.pdf.libreoffice-conversion-client :as libreoffice-client]
            [lupapiste-commons.attachment-types :as attachment-types]
            [lupapalvelu.preview :as preview])
  (:import [java.util.zip ZipOutputStream ZipEntry]
           [java.io File FilterInputStream]
           [org.apache.commons.io FilenameUtils]
           [java.util.concurrent Executors ThreadFactory]))

(defn thread-factory []
  (let [security-manager (System/getSecurityManager)
        thread-group (if security-manager
                       (.getThreadGroup security-manager)
                       (.getThreadGroup (Thread/currentThread)))]
    (reify
      ThreadFactory
      (newThread [this runnable]
        (doto (Thread. thread-group runnable "preview-worker")
          (.setDaemon true)
          (.setPriority Thread/NORM_PRIORITY))))))

(defonce preview-threadpool (Executors/newFixedThreadPool 1 (thread-factory)))


;;
;; Metadata
;;

(def attachment-types-osapuoli attachment-types/osapuolet)

(def attachment-meta-types [:size :scale :op :contents])

(def attachment-scales
  [:1:20
   :1:50
   :1:100
   :1:200
   :1:500
   :muu])

(def attachment-sizes
  [:A0
   :A1
   :A2
   :A3
   :A4
   :A5
   :B0
   :B1
   :B2
   :B3
   :B4
   :B5
   :muu])

(def attachment-states #{:ok :requires_user_action :requires_authority_action})

(def- attachment-types-by-permit-type-unevaluated
  {:R 'attachment-types/Rakennusluvat
   :P 'attachment-types/Rakennusluvat
   :YA 'attachment-types/YleistenAlueidenLuvat
   :YI 'attachment-types/Ymparistoilmoitukset
   :YL 'attachment-types/Ymparistolupa
   :YM 'attachment-types/MuutYmparistoluvat
   :VVVL 'attachment-types/Ymparistoilmoitukset
   :MAL 'attachment-types/Maa-ainesluvat
   :MM 'attachment-types/Kiinteistotoimitus
   :KT 'attachment-types/Kiinteistotoimitus})

(def- attachment-types-by-permit-type (eval attachment-types-by-permit-type-unevaluated))

(def operation-specific-attachment-types #{{:type-id :pohjapiirros       :type-group :paapiirustus}
                                           {:type-id :leikkauspiirros    :type-group :paapiirustus}
                                           {:type-id :julkisivupiirros   :type-group :paapiirustus}
                                           {:type-id :yhdistelmapiirros  :type-group :paapiirustus}
                                           {:type-id :erityissuunnitelma :type-group :rakentamisen_aikaiset}
                                           {:type-id :energiatodistus    :type-group :muut}
                                           {:type-id :korjausrakentamisen_energiaselvitys :type-group :muut}
                                           {:type-id :rakennuksen_tietomalli_BIM :type-group :muut}})

(def all-attachment-type-ids
  (->> (vals attachment-types-by-permit-type)
       (apply concat)
       (#(flatten (map second (partition 2 %))))
       (set)))

(def all-attachment-type-groups
  (->> (vals attachment-types-by-permit-type)
       (apply concat)
       (#(map first (partition 2 %)))
       (set)))

(def archivability-errors #{:invalid-mime-type :invalid-pdfa :invalid-tiff})

(def AttachmentAuthUser (let [SummaryAuthUser (assoc user/SummaryUser :role (sc/enum "stamper" "uploader"))]
                          (sc/if :id
                            SummaryAuthUser
                            (select-keys SummaryAuthUser [:firstName :lastName :role]))))

(def VersionNumber {:minor                             sc/Int
                    :major                             sc/Int})

(def Target     {:id                                   ssc/ObjectIdStr
                 :type                                 sc/Keyword
                 (sc/optional-key :urlHash)            sc/Str})

(def Source     {:id                                   ssc/ObjectIdStr
                 :type                                 sc/Str})

(def Operation  {:id                                   ssc/ObjectIdStr
                 (sc/optional-key :optional)           [sc/Str] ;; only empty arrays @ talos
                 (sc/optional-key :name)               sc/Str
                 (sc/optional-key :description)        (sc/maybe sc/Str)
                 (sc/optional-key :created)            ssc/Timestamp})

(def Signature  {:user                                 user/SummaryUser
                 :created                              ssc/Timestamp
                 :version                              VersionNumber})

(def Version    {:version                              VersionNumber
                 :fileId                               sc/Str
                 :created                              ssc/Timestamp
                 :user                                 (sc/if :id
                                                         user/SummaryUser
                                                         (select-keys user/User [:firstName :lastName]))
                 :filename                             sc/Str
                 :contentType                          sc/Str
                 :size                                 (sc/maybe sc/Int)
                 (sc/optional-key :stamped)            sc/Bool
                 (sc/optional-key :archivable)         (sc/maybe sc/Bool)
                 (sc/optional-key :archivabilityError) (sc/maybe (apply sc/enum archivability-errors))
                 (sc/optional-key :missing-fonts)      (sc/maybe [sc/Str])})

(def Type       {:type-id                              (apply sc/enum all-attachment-type-ids)
                 :type-group                           (apply sc/enum all-attachment-type-groups)})

(def Attachment {:id                                   sc/Str
                 :type                                 Type
                 :modified                             ssc/Timestamp
                 (sc/optional-key :sent)               ssc/Timestamp
                 :locked                               sc/Bool
                 (sc/optional-key :readOnly)           sc/Bool
                 :applicationState                     (apply sc/enum states/all-states)
                 :state                                (apply sc/enum attachment-states)
                 :target                               (sc/maybe Target)
                 (sc/optional-key :source)             Source
                 :required                             sc/Bool
                 :requestedByAuthority                 sc/Bool
                 :notNeeded                            sc/Bool
                 :forPrinting                          sc/Bool
                 :op                                   (sc/maybe Operation)
                 :signatures                           [Signature]
                 :versions                             [Version]
                 (sc/optional-key :latestVersion)      (sc/maybe Version)
                 (sc/optional-key :contents)           (sc/maybe sc/Str)
                 (sc/optional-key :scale)              (apply sc/enum attachment-scales)
                 (sc/optional-key :size)               (apply sc/enum attachment-sizes)
                 :auth                                 [AttachmentAuthUser]
                 (sc/optional-key :metadata)           {sc/Any sc/Any}
                 (sc/optional-key :copy-of)            Source})

;; Helper for reporting purposes
(defn localised-attachments-by-permit-type [permit-type]
  (let [localize-attachment-section
        (fn [lang [title attachment-names]]
          [(i18n/localize lang (ss/join "." ["attachmentType" (name title) "_group_label"]))
           (reduce
             (fn [result attachment-name]
               (let [lockey                    (ss/join "." ["attachmentType" (name title) (name attachment-name)])
                     localized-attachment-name (i18n/localize lang lockey)]
                 (conj
                   result
                   (ss/join \tab [(name attachment-name) localized-attachment-name]))))
             []
             attachment-names)])]
    (reduce
      (fn [accu lang]
        (assoc accu (keyword lang)
          (->> (get attachment-types-by-permit-type (keyword permit-type))
            (partition 2)
            (map (partial localize-attachment-section lang))
            vec)))
      {}
     ["fi" "sv"])))

(defn print-attachment-types-by-permit-type []
  (let [permit-types-with-names (into {}
                                      (for [[k v] attachment-types-by-permit-type-unevaluated]
                                        [k (name v)]))]
    (doseq [[permit-type permit-type-name] permit-types-with-names]
    (println permit-type-name)
    (doseq [[group-name types] (:fi (localised-attachments-by-permit-type permit-type))]
      (println "\t" group-name)
      (doseq [type types]
        (println "\t\t" type))))))

;;
;; Api
;;

(defn by-file-ids [file-ids attachment]
  (let [file-id-set (set file-ids)
        attachment-file-ids (map :fileId (:versions attachment))]
    (some #(file-id-set %) attachment-file-ids)))

(defn get-attachments-infos
  "gets attachments from application"
  [application attachment-ids]
  (let [ids (set attachment-ids)] (filter (comp ids :id) (:attachments application))))

(defn get-attachment-info
  "gets an attachment from application or nil"
  [application attachment-id]
  (first (get-attachments-infos application [attachment-id])))

(defn create-sent-timestamp-update-statements [attachments file-ids timestamp]
  (mongo/generate-array-updates :attachments attachments (partial by-file-ids file-ids) :sent timestamp))

(defn get-attachment-types-by-permit-type
  "Returns partitioned list of allowed attachment types or throws exception"
  [permit-type]
  {:pre [permit-type]}
  (if-let [types (get attachment-types-by-permit-type (keyword permit-type))]
    (partition 2 types)
    (fail! (str "unsupported permit-type: " (name permit-type)))))

(defn get-attachment-types-for-application
  [application]
  {:pre [application]}
  (get-attachment-types-by-permit-type (:permitType application)))

(defn make-attachment [now target required? requested-by-authority? locked? application-state op attachment-type metadata & [attachment-id contents read-only? copy-of]]
  (cond-> {:id (or attachment-id (mongo/create-id))
           :type attachment-type
           :modified now
           :locked locked?
           :readOnly (boolean read-only?)
           :applicationState (if (and (= "verdict" (:type target)) (not (states/post-verdict-states (keyword application-state))))
                               "verdictGiven"
                               application-state)
           :state :requires_user_action
           :target target
           :required required?       ;; true if the attachment is added from from template along with the operation, or when attachment is requested by authority
           :requestedByAuthority requested-by-authority?  ;; true when authority is adding a new attachment template by hand
           :notNeeded false
           :forPrinting false
           :op op
           :signatures []
           :versions []
           :auth []
           :contents contents}
          (map? copy-of) (assoc :copy-of copy-of)
          (seq metadata) (assoc :metadata metadata)))

(defn make-attachments
  "creates attachments with nil target"
  [now application-state attachment-types-with-metadata locked? required? requested-by-authority?]
  (map #(make-attachment now nil required? requested-by-authority? locked? application-state nil (:type %) (:metadata %)) attachment-types-with-metadata))

(defn- default-metadata-for-attachment-type [type {:keys [:organization :tosFunction]}]
  (let [metadata (tos/metadata-for-document organization tosFunction type)]
    (if (seq metadata)
      metadata
      {:nakyvyys :julkinen})))

(defn create-attachment [application attachment-type op now target locked? required? requested-by-authority? & [attachment-id contents read-only? copy-of]]
  {:pre [(map? application)]}
  (let [metadata (default-metadata-for-attachment-type attachment-type application)
        attachment (make-attachment now target required? requested-by-authority? locked? (:state application) op attachment-type metadata attachment-id contents read-only? copy-of)]
    (update-application
      (application->command application)
      {$set {:modified now}
       $push {:attachments attachment}})

    (:id attachment)))

(defn create-attachments [application attachment-types now locked? required? requested-by-authority?]
  {:pre [(map? application)]}
  (let [attachment-types-with-metadata (map (fn [type] {:type type :metadata (default-metadata-for-attachment-type type application)}) attachment-types)
        attachments (make-attachments now (:state application) attachment-types-with-metadata locked? required? requested-by-authority?)]
    (update-application
      (application->command application)
      {$set {:modified now}
       $push {:attachments {$each attachments}}})

    (map :id attachments)))

(defn next-attachment-version [{major :major minor :minor} user]
  (let [major (or major 0)
        minor (or minor 0)]
    (if (user/authority? user)
      {:major major, :minor (inc minor)}
      {:major (inc major), :minor 0})))

(defn attachment-latest-version [attachments attachment-id]
  (:version (:latestVersion (some #(when (= attachment-id (:id %)) %) attachments))))

(defn version-number
  [{{:keys [major minor]} :version}]
  (+ (* 1000 major) minor))

(defn latest-version-after-removing-file [attachments attachment-id fileId]
  (let [attachment (some #(when (= attachment-id (:id %)) %) attachments)
        versions   (:versions attachment)
        stripped   (filter #(not= (:fileId %) fileId) versions)
        sorted     (sort-by version-number stripped)
        latest     (last sorted)]
    latest))

(defn get-version-by-file-id [attachment fileId]
  (->> attachment
       :versions
       (filter #(= (:fileId %) fileId))
       first))

(defn get-version-number
  [{:keys [attachments] :as application} attachment-id fileId]
  (let [attachment (get-attachment-info application attachment-id)
        version    (get-version-by-file-id attachment fileId)]
    (:version version)))

(defn set-attachment-version
  ([options]
    {:pre [(map? options)]}
    (set-attachment-version options 5))
  ([{:keys [application attachment-id file-id filename content-type size comment-text now user stamped make-comment state target archivable archivabilityError missing-fonts]
     :or {make-comment true state :requires_authority_action} :as options}
    retry-limit]
    {:pre [(map? options) (map? application) (string? attachment-id) (string? file-id) (string? filename) (string? content-type) (number? size) (number? now) (map? user) (not (nil? stamped))]}
    ; TODO refactor to return version-model and mongo updates, so that updates can be merged into single statement
    (if (pos? retry-limit)
      (let [latest-version (attachment-latest-version (application :attachments) attachment-id)
            next-version (next-attachment-version latest-version user)
            user-summary (user/summary user)
            user-role (if stamped :stamper :uploader)
            version-model {:version  next-version
                           :fileId   file-id
                           :created  now
                           :user     user-summary
                           ; File name will be presented in ASCII when the file is downloaded.
                           ; Conversion could be done here as well, but we don't want to lose information.
                           :filename filename
                           :contentType content-type
                           :size size
                           :stamped stamped
                           :archivable archivable
                           :archivabilityError archivabilityError
                           :missing-fonts missing-fonts}

            comment-target {:type :attachment
                            :id attachment-id
                            :version next-version
                            :filename filename
                            :fileId file-id}

            result-count (update-application
                           (application->command application)
                           {:attachments {$elemMatch {:id attachment-id
                                                      :latestVersion.version.major (:major latest-version)
                                                      :latestVersion.version.minor (:minor latest-version)}}}
                           (util/deep-merge
                             (when make-comment (comment/comment-mongo-update (:state application) comment-text comment-target :system false user nil now))
                             (when target {$set {:attachments.$.target target}})
                             {$set {:modified now
                                    :attachments.$.modified now
                                    :attachments.$.state  state
                                    :attachments.$.latestVersion version-model}
                              $push {:attachments.$.versions version-model}
                              $addToSet {:attachments.$.auth (user/user-in-role user-summary user-role)}})
                           true)]
        ; Check return value and try again with new version number
        (if (pos? result-count)
          (assoc version-model :id attachment-id)
          (do
            (errorf
              "Latest version of attachment %s changed before new version could be saved, retry %d time(s)."
              attachment-id retry-limit)
            (set-attachment-version (assoc options :application (mongo/by-id :applications (:id application))) (dec retry-limit)))))
      (do
        (error "Concurrency issue: Could not save attachment version meta data.")
        nil))))

(defn update-attachment-key [command attachmentId k v now & {:keys [set-app-modified? set-attachment-modified?] :or {set-app-modified? true set-attachment-modified? true}}]
  (let [update-key (->> (name k) (str "attachments.$.") keyword)]
    (update-application command
      {:attachments {$elemMatch {:id attachmentId}}}
      {$set (merge
              {update-key v}
              (when set-app-modified? {:modified now})
              (when set-attachment-modified? {:attachments.$.modified now}))})))

(defn update-latest-version-content
  "Updates latest version when version is stamped"
  [user application attachment-id file-id size now]
  (let [attachment (get-attachment-info application attachment-id)
        latest-version-index (-> attachment :versions count dec)
        latest-version-path (str "attachments.$.versions." latest-version-index ".")
        old-file-id (get-in attachment [:latestVersion :fileId])
        user-summary (user/summary user)]

    (when-not (= old-file-id file-id)
      (mongo/delete-file-by-id old-file-id))

    (update-application
      (application->command application)
      {:attachments {$elemMatch {:id attachment-id}}}
      {$set {:modified now
             :attachments.$.modified now
             (str latest-version-path "user") user-summary
             (str latest-version-path "fileId") file-id
             (str latest-version-path "size") size
             (str latest-version-path "created") now
             :attachments.$.latestVersion.user user-summary
             :attachments.$.latestVersion.fileId file-id
             :attachments.$.latestVersion.size size
             :attachments.$.latestVersion.created now}})))

(defn- update-or-create-attachment
  "If the attachment-id matches any old attachment, a new version will be added.
   Otherwise a new attachment is created."
  [{:keys [application attachment-id attachment-type op created user target locked required contents read-only copy-of] :as options}]
  {:pre [(map? application)]}
  (let [requested-by-authority? (and (ss/blank? attachment-id) (user/authority? user))
        att-id (cond
                 (ss/blank? attachment-id) (create-attachment application attachment-type op created target locked required requested-by-authority? nil contents read-only copy-of)
                 (pos? (mongo/count :applications {:_id (:id application) :attachments.id attachment-id})) attachment-id
                 :else (create-attachment application attachment-type op created target locked required requested-by-authority? attachment-id contents read-only copy-of))]
    (set-attachment-version (assoc options :attachment-id att-id :now created :stamped false))))

(defn parse-attachment-type [attachment-type]
  (if-let [match (re-find #"(.+)\.(.+)" (or attachment-type ""))]
    (let [[type-group type-id] (->> match (drop 1) (map keyword))]
      {:type-group type-group :type-id type-id})))

(defn allowed-attachment-types-contain? [allowed-types {:keys [type-group type-id]}]
  (let [type-group (keyword type-group)
        type-id (keyword type-id)]
    (if-let [types (some (fn [[group-name group-types]] (if (= (keyword group-name) type-group) group-types)) allowed-types)]
      (some #(= (keyword %) type-id) types))))

(defn get-attachment-info-by-file-id
  "gets an attachment from application or nil"
  [{:keys [attachments]} file-id]
  (first
    (filter
      (partial by-file-ids #{file-id})
      attachments)))

(defn attachment-file-ids
  "Gets all file-ids from attachment."
  [application attachment-id]
  (->> (get-attachment-info application attachment-id) :versions (map :fileId)))

(defn attachment-latest-file-id
  "Gets latest file-id from attachment."
  [application attachment-id]
  (last (attachment-file-ids application attachment-id)))

(defn file-id-in-application?
  "tests that file-id is referenced from application"
  [application attachment-id file-id]
  (let [file-ids (attachment-file-ids application attachment-id)]
    (boolean (some #{file-id} file-ids))))

(defn delete-attachment
  "Delete attachement with all it's versions. does not delete comments. Non-atomic operation: first deletes files, then updates document."
  [{:keys [attachments] :as application} attachment-id]
  (info "1/3 deleting files of attachment" attachment-id)
  (dorun (map mongo/delete-file-by-id (attachment-file-ids application attachment-id)))
  (info "2/3 deleted files of attachment" attachment-id)
  (update-application (application->command application) {$pull {:attachments {:id attachment-id}}})
  (info "3/3 deleted meta-data of attachment" attachment-id))

(defn delete-attachment-version
  "Delete attachment version. Is not atomic: first deletes file, then removes application reference."
  [{:keys [id attachments] :as application} attachment-id fileId]
  (let [latest-version (latest-version-after-removing-file attachments attachment-id fileId)]
    (infof "1/3 deleting file %s of attachment %s" fileId attachment-id)
    (mongo/delete-file-by-id fileId)
    (infof "2/3 deleted file %s of attachment %s" fileId attachment-id)
    (update-application
      (application->command application)
      {:attachments {$elemMatch {:id attachment-id}}}
      {$pull {:attachments.$.versions {:fileId fileId}
              :attachments.$.signatures {:version (get-version-number application attachment-id fileId)}}
       $set  {:attachments.$.latestVersion latest-version}})
    (infof "3/3 deleted meta-data of file %s of attachment" fileId attachment-id)))

(defn get-attachment-file-as
  "Returns the attachment file if user has access to application and the attachment, otherwise nil."
  [user file-id]
  (when-let [attachment-file (mongo/download file-id)]
    (when-let [application (get-application-as (:application attachment-file) user :include-canceled-apps? true)]
      (when (and (seq application) (access/can-access-attachment-file? user file-id application)) attachment-file))))

(defn get-attachment-file
  "Returns the attachment file without access checking, otherwise nil."
  [file-id]
  (when-let [attachment-file (mongo/download file-id)]
    (when-let [application (get-application-no-access-checking (:application attachment-file))]
      (when (seq application) attachment-file))))

(defn output-attachment
  [file-id download? attachment-fn]
  (if-let [attachment (attachment-fn file-id)]
    (let [filename (ss/encode-filename (:file-name attachment))
          response {:status 200
                    :body ((:content attachment))
                    :headers {"Content-Type" (:content-type attachment)
                              "Content-Length" (str (:content-length attachment))}}]
      (if download?
        (assoc-in response [:headers "Content-Disposition"] (format "attachment;filename=\"%s\"" filename))
        (update response :headers merge http/no-cache-headers)))
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "404"}))

(defn create-preview
  [file-id filename content-type content application-id & [db-name]]
  (when (preview/converter content-type)
    (mongo/with-db (or db-name mongo/default-db-name)
      (mongo/upload (str file-id "-preview") (str (FilenameUtils/getBaseName filename) ".jpg") "image/jpg" (preview/placeholder-image) :application application-id)
      (when-let [preview-content (util/timing (format "Creating preview: id=%s, type=%s file=%s" file-id content-type filename)
                                              (with-open [content ((:content (mongo/download file-id)))]
                                                (preview/create-preview content content-type)))]
        (debugf "Saving preview: id=%s, type=%s file=%s" file-id content-type filename)
        (mongo/upload (str file-id "-preview") (str (FilenameUtils/getBaseName filename) ".jpg") "image/jpg" preview-content :application application-id)))))

(defn output-attachment-preview
  "Outputs attachment preview creating it if is it does not already exist"
  [file-id attachment-fn]
  (let [preview-id (str file-id "-preview")]
    (when (zero? (mongo/count :fs.files {:_id preview-id}))
      (let [attachment (get-attachment-file file-id)
            file-name (:file-name attachment)
            content-type (:content-type attachment)
            content-fn (:content attachment)
            application-id (:application attachment)]
        (assert content-fn (str "content for file " file-id))
        (create-preview file-id file-name content-type (content-fn) application-id)))
    (output-attachment preview-id false attachment-fn)))

(defn pre-process-attachment [{:keys [attachment-type filename content]}]
  (if (and libreoffice-client/enabled? (= attachment-type {:type-group "muut" :type-id "paatosote"}))
    {:filename (str (FilenameUtils/removeExtension filename) ".pdf")
     :content  (:body (libreoffice-client/convert-to-pdfa filename content))}
    {:filename filename :content content}))

(defn attach-file!
  "1) Converts file to PDF/A, if required by attachment type and
   1) uploads the file to MongoDB and
   2) creates a preview image and
   3) creates a corresponding attachment structure to application
   Content can be a file or input-stream.
   Returns attachment version."
  [options]
  {:pre [(map? (:application options))]}
  (let [db-name mongo/*db-name* ; pass db-name to threadpool context
        file-id (mongo/create-id)
        application-id (-> options :application :id)
        {:keys [filename content]} (pre-process-attachment options)
        sanitized-filename (mime/sanitize-filename filename)
        content-type (mime/mime-type sanitized-filename)
        options (merge options {:file-id file-id
                                :filename sanitized-filename
                                :content-type content-type})]
    (mongo/upload file-id sanitized-filename content-type content :application application-id)
    (.submit preview-threadpool #(create-preview file-id sanitized-filename content-type content application-id db-name))
    (update-or-create-attachment options)))

(defn get-attachments-by-operation
  [{:keys [attachments] :as application} op-id]
  (filter #(= (:id (:op %)) op-id) attachments))

(defn- append-gridfs-file [zip {:keys [filename fileId]}]
  (when fileId
    (.putNextEntry zip (ZipEntry. (ss/encode-filename (str fileId "_" filename))))
    (with-open [in ((:content (mongo/download fileId)))]
      (io/copy in zip))))

(defn- append-stream [zip file-name in]
  (when in
    (.putNextEntry zip (ZipEntry. (ss/encode-filename file-name)))
    (io/copy in zip)))

(defn get-all-attachments
  "Returns attachments as zip file. If application and lang, application and submitted application PDF are included."
  [attachments & [application lang]]
  (let [temp-file (File/createTempFile "lupapiste.attachments." ".zip.tmp")]
    (debugf "Created temporary zip file for attachments: %s" (.getAbsolutePath temp-file))
    (with-open [out (io/output-stream temp-file)]
      (let [zip (ZipOutputStream. out)]
        ; Add all attachments:
        (doseq [attachment attachments]
          (append-gridfs-file zip (-> attachment :versions last)))

        (when (and application lang)
          ; Add submitted PDF, if exists:
          (when-let [submitted-application (mongo/by-id :submitted-applications (:id application))]
            (append-stream zip (i18n/loc "attachment.zip.pdf.filename.submitted") (pdf-export/generate submitted-application lang)))
          ; Add current PDF:
          (append-stream zip (i18n/loc "attachment.zip.pdf.filename.current") (pdf-export/generate application lang)))
        (.finish zip)))
    (debugf "Size of the temporary zip file: %d" (.length temp-file))
    temp-file))

(defn temp-file-input-stream [^File file]
  (let [i (io/input-stream file)]
    (proxy [FilterInputStream] [i]
      (close []
        (proxy-super close)
        (when (= (io/delete-file file :could-not) :could-not)
          (warnf "Could not delete temporary file: %s" (.getAbsolutePath file)))))))

(defn- post-process-attachment [attachment]
  (assoc attachment :isPublic (metadata/public-attachment? attachment)))

(defn post-process-attachments [application]
  (update-in application [:attachments] (partial map post-process-attachment)))
