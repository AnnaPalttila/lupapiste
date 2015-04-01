(ns lupapalvelu.integrations-api
  "API for commands/functions working with integrations (ie. KRYSP, Asianhallinta)"
  (:require [taoensso.timbre :as timbre :refer [infof error]]
            [monger.operators :refer [$in $set]]
            [lupapalvelu.action :refer [defcommand update-application notify] :as action]
            [lupapalvelu.application :as application]
            [lupapalvelu.application-meta-fields :as meta-fields]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.document.commands :as commands]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.foreman :as foreman]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.user :as user]
            [lupapalvelu.xml.krysp.application-as-krysp-to-backing-system :as mapping-to-krysp]
            [lupapalvelu.xml.krysp.reader :as krysp-reader]
            [lupapalvelu.xml.asianhallinta.core :as ah]
            [sade.core :refer :all]
            [sade.strings :as ss]
            [sade.util :as util]))

;;
;; Application approval
;;

; TODO needs refactoring
(defn- update-link-permit-data-with-kuntalupatunnus-from-verdict [application]
  (let [link-permit-app-id (-> application :linkPermitData first :id)
        link-permit-app (domain/get-application-no-access-checking link-permit-app-id)
        kuntalupatunnus (-> link-permit-app :verdicts first :kuntalupatunnus)]
    ; TODO why we check only link permit data on index 0?
    (if kuntalupatunnus
      (-> application
         (assoc-in [:linkPermitData 0 :lupapisteId] link-permit-app-id)
         (assoc-in [:linkPermitData 0 :id] kuntalupatunnus)
         (assoc-in [:linkPermitData 0 :type] "kuntalupatunnus"))
      (if (and (foreman/foreman-app? application) (some #{(keyword (:state link-permit-app))} meta-fields/post-sent-states))
        application
        (do
          (error "Not able to get a kuntalupatunnus for the application  " (:id application) " from it's link permit's (" link-permit-app-id ") verdict."
                 " Associated Link-permit data: " (:linkPermitData application))
          (if (foreman/foreman-app? application)
            (fail! :error.link-permit-app-not-in-post-sent-state)
            (fail! :error.kuntalupatunnus-not-available-from-verdict)))))))

(defn- do-approve [application created id lang jatkoaika-app? do-rest-fn]
  (let [organization (organization/get-organization (:organization application))]
    (if (organization/has-ftp-user? organization (permit/permit-type application))
      (or
        (application/validate-link-permits application)
        (let [sent-file-ids (if jatkoaika-app?
                              (mapping-to-krysp/save-jatkoaika-as-krysp application lang organization)
                              (let [submitted-application (mongo/by-id :submitted-applications id)]
                                (mapping-to-krysp/save-application-as-krysp application lang submitted-application organization)))
              attachments-updates (or (attachment/create-sent-timestamp-update-statements (:attachments application) sent-file-ids created) {})]
          (do-rest-fn attachments-updates)))
      ;; SFTP user not defined for the organization -> let the approve command pass
      (do-rest-fn nil))))

(defcommand approve-application
  {:parameters [id lang]
   :user-roles #{:authority}
   :notified   true
   :on-success (notify :application-state-change)
   :states     [:submitted :complement-needed]}
  [{:keys [application created user] :as command}]
  (let [jatkoaika-app? (= :ya-jatkoaika (-> application :operations first :name keyword))
        foreman-notice? (when foreman/foreman-app?
                          (= "ilmoitus" (-> (domain/get-document-by-name application "tyonjohtaja-v2") :data :ilmoitusHakemusValitsin :value)))
        app-updates (merge
                      {:modified created
                       :sent created
                       :authority (if (seq (:authority application)) (:authority application) (user/summary user))} ; LUPA-1450
                      (if (or jatkoaika-app? foreman-notice?)
                        {:state :closed :closed created}
                        {:state :sent}))
        application (-> application
                      meta-fields/enrich-with-link-permit-data
                      (#(if (= "lupapistetunnus" (-> % :linkPermitData first :type))
                         (update-link-permit-data-with-kuntalupatunnus-from-verdict %)
                         %))
                      (merge app-updates))
        mongo-query (if (or jatkoaika-app? foreman-notice?)
                      {:state {$in ["submitted" "complement-needed"]}}
                      {})
        indicator-updates (application/mark-indicators-seen-updates application user created)
        do-update (fn [attachments-updates]
                    (update-application command
                      mongo-query
                      {$set (util/deep-merge app-updates attachments-updates indicator-updates)})
                    (ok :integrationAvailable (not (nil? attachments-updates))))]

    (do-approve application created id lang jatkoaika-app? do-update)))


;;
;; krysp enrichment
;;

(defn add-value-metadata [m meta-data]
  (reduce (fn [r [k v]] (assoc r k (if (map? v) (add-value-metadata v meta-data) (assoc meta-data :value v)))) {} m))

(defn- load-building-data [url property-id building-id overwrite-all?]
  (let [all-data (krysp-reader/->rakennuksen-tiedot (krysp-reader/building-xml url property-id) building-id)]
    (if overwrite-all?
      all-data
      (select-keys all-data (keys krysp-reader/empty-building-ids)))))

(defcommand merge-details-from-krysp
  {:parameters [id documentId path buildingId overwrite collection]
   :input-validators [commands/validate-collection
                      (partial action/non-blank-parameters [:documentId :path])
                      (partial action/boolean-parameters [:overwrite])]
   :user-roles #{:applicant :authority}
   :states     (action/all-application-states-but [:sent :verdictGiven :constructionStarted :closed :canceled])}
  [{created :created {:keys [organization propertyId] :as application} :application :as command}]
  (if-let [{url :url} (organization/get-krysp-wfs application)]
    (let [document     (commands/by-id application collection documentId)
          schema       (schemas/get-schema (:schema-info document))
          clear-ids?   (or (ss/blank? buildingId) (= "other" buildingId))
          base-updates (concat
                         (commands/->model-updates [[path buildingId]])
                         (tools/path-vals
                           (if clear-ids?
                             krysp-reader/empty-building-ids
                             (load-building-data url propertyId buildingId overwrite))))
          ; Path should exist in schema!
          updates      (filter (fn [[path _]] (model/find-by-name (:body schema) path)) base-updates)]
      (infof "merging data into %s %s" (get-in document [:schema-info :name]) (:id document))
      (commands/persist-model-updates application collection document updates created :source "krysp")
      (ok))
    (fail :error.no-legacy-available)))

;;
;; Building info
;;

(defcommand get-building-info-from-wfs
  {:parameters [id]
   :user-roles #{:applicant :authority}
   :states     (action/all-application-states-but [:sent :verdictGiven :constructionStarted :closed :canceled])}
  [{{:keys [organization propertyId] :as application} :application}]
  (if-let [{url :url} (organization/get-krysp-wfs application)]
    (let [kryspxml  (krysp-reader/building-xml url propertyId)
          buildings (krysp-reader/->buildings-summary kryspxml)]
      (ok :data buildings))
    (fail :error.no-legacy-available)))

;;
;; Asianhallinta
;;

(defn- fetch-linked-kuntalupatunnus [application]
  "Fetch kuntalupatunnus from application's link permit's verdicts"
  (when-let [link-permit-app (application/get-link-permit-app application)]
    (-> link-permit-app :verdicts first :kuntalupatunnus)))

(defn- has-asianhallinta-operation [_ {:keys [operations]}]
  (when-not (operations/get-operation-metadata (:name (first operations)) :asianhallinta)
    (fail :error.operations.asianhallinta-disabled)))

(defcommand application-to-asianhallinta
  {:parameters [id lang]
   :user-roles #{:authority}
   :notified   true
   :on-success (notify :application-state-change)
   :pre-checks [has-asianhallinta-operation]
   :states     [:submitted :complement-needed]}
  [{:keys [application created user]:as command}]
  (let [application (meta-fields/enrich-with-link-permit-data application)
        application (if-let [kuntalupatunnus (fetch-linked-kuntalupatunnus application)]
                      (update-in application 
                                 [:linkPermitData] 
                                 conj {:id kuntalupatunnus
                                       :type "kuntalupatunnus"})
                      application)
        submitted-application (mongo/by-id :submitted-applications id)
        app-updates {:modified created
                     :sent created
                     :authority (if (seq (:authority application)) (:authority application) (user/summary user))
                     :state :sent}
        organization (organization/get-organization (:organization application))
        indicator-updates (application/mark-indicators-seen-updates application user created)
        file-ids (ah/save-as-asianhallinta application lang submitted-application organization) ; Writes to disk
        attachments-updates (or (attachment/create-sent-timestamp-update-statements (:attachments application) file-ids created) {})]
    (update-application command {$set (util/deep-merge app-updates attachments-updates indicator-updates)})
    (ok)))

(defn- update-kuntalupatunnus [application]
  (if-let [kuntalupatunnus (fetch-linked-kuntalupatunnus application)]
    (update-in application
               [:linkPermitData]
               conj {:id kuntalupatunnus
                     :type "kuntalupatunnus"})
    application))

(defcommand attachments-to-asianhallinta
  {:parameters [id lang attachmentIds]
   :user-roles #{:authority}
   :pre-checks [has-asianhallinta-operation]
   :states     [:verdictGiven :constructionStarted :sent]
   :description "Sends such selected attachments to backing system that are not yet sent."}
  [{:keys [created application user] :as command}]

  (let [attachments-wo-sent-timestamp (filter
                                        #(and
                                          (-> % :versions count pos?)
                                          (or
                                            (not (:sent %))
                                            (> (-> % :versions last :created) (:sent %)))
                                          (not (#{"verdict" "statement"} (-> % :target :type)))
                                          (some #{(:id %)} attachmentIds))
                                        (:attachments application))]
       (if (pos? (count attachments-wo-sent-timestamp))
         (let [application (meta-fields/enrich-with-link-permit-data application)
               application (update-kuntalupatunnus application)
               sent-file-ids (ah/save-as-asianhallinta-asian-taydennys application attachments-wo-sent-timestamp lang)
               data-argument (attachment/create-sent-timestamp-update-statements (:attachments application) sent-file-ids created)]
              (update-application command {$set data-argument})
              (ok))
         (fail :error.sending-unsent-attachments-failed))))