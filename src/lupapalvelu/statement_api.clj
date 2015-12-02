(ns lupapalvelu.statement-api
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error fatal]]
            [monger.operators :refer :all]
            [sade.env :as env]
            [sade.util :as util]
            [sade.core :refer :all]
            [sade.strings :as ss]
            [sade.validators :as v]
            [lupapalvelu.action :refer [defquery defcommand update-application executed] :as action]
            [lupapalvelu.comment :as comment]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.statement :refer :all]
            [lupapalvelu.states :as states]
            [lupapalvelu.tiedonohjaus :as t]
            [lupapalvelu.user :refer [with-user-by-email] :as user]
            [lupapalvelu.user-api :as user-api]
            [lupapalvelu.child-to-attachment :as child-to-attachment]))

;;
;; Authority Admin operations
;;

(defn- fetch-organization-statement-givers [org-id]
  (let [organization (organization/get-organization org-id)
        permitPersons (or (:statementGivers organization) [])]
    (ok :data permitPersons)))

(defquery get-organizations-statement-givers
  {:user-roles #{:authorityAdmin}}
  [{user :user}]
  (let [org-id (user/authority-admins-organization-id user)]
    (fetch-organization-statement-givers org-id)))

(defn- statement-giver-model [{{:keys [text organization]} :data} _ __]
  {:text text
   :organization-fi (:fi (:name organization))
   :organization-sv (:sv (:name organization))})


(notifications/defemail :add-statement-giver
  {:recipients-fn  notifications/from-user
   :subject-key    "application.statements"
   :model-fn       statement-giver-model})

(defcommand create-statement-giver
  {:parameters [email text]
   :input-validators [(partial action/non-blank-parameters [:email])
                      action/email-validator]
   :notified   true
   :user-roles #{:authorityAdmin}}
  [{user :user}]
  (let [organization (organization/get-organization (user/authority-admins-organization-id user))
        email           (user/canonize-email email)
        statement-giver-id (mongo/create-id)]
    (if-let [user (user/get-user-by-email email)]
      (do
        (when-not (user/authority? user) (fail! :error.not-authority))
        (organization/update-organization (:id organization) {$push {:statementGivers {:id statement-giver-id
                                                                                       :text text
                                                                                       :email email
                                                                                       :name (str (:firstName user) " " (:lastName user))}}})
        (notifications/notify! :add-statement-giver  {:user user :data {:text text :organization organization}})
        (ok :id statement-giver-id))
      (fail :error.user-not-found))))

(defcommand delete-statement-giver
  {:parameters [personId]
   :user-roles #{:authorityAdmin}}
  [{user :user}]
  (organization/update-organization
    (user/authority-admins-organization-id user)
    {$pull {:statementGivers {:id personId}}}))

;;
;; Authority operations
;;

(defquery get-possible-statement-statuses
  {:description "Provides the possible statement statuses according to the krysp version in use."
   :parameters [:id]
   :user-roles #{:authority :applicant}
   :user-authz-roles action/all-authz-roles
   :org-authz-roles action/reader-org-authz-roles
   :states states/all-application-states}
  [{application :application}]
  (ok :data (possible-statement-statuses application)))

(defquery get-statement-givers
  {:parameters [:id]
   :user-roles #{:authority :applicant}
   :user-authz-roles action/default-authz-writer-roles
   :states states/all-application-states}
  [{application :application}]
  (let [org-id (:organization application)]
    (fetch-organization-statement-givers org-id)))

(defquery should-see-unsubmitted-statements
  {:description "Pseudo query for UI authorization logic"
   :parameters [:id]
   :states (states/all-application-states-but [:draft])
   :user-roles #{:authority :applicant}
   :user-authz-roles #{:statementGiver}}
  [_])

(defquery can-operate-on-statement
  {:description "Pseudo query for UI authorization logic"
   :parameters [:id]
   :states (states/all-application-states-but [:draft])
   :user-roles #{:authority :applicant}
   :user-authz-roles #{:statementGiver}
   :pre-checks [statement-exists
                authority-or-statement-owner-applicant]}
  [_])

(notifications/defemail :request-statement
  {:recipients-fn  :recipients
   :subject-key    "statement-request"
   :show-municipality-in-subject true})

(defn- make-details [inviter now persons metadata saateText dueDate]
  (map
    (fn [person]
      (let [user (if-not (ss/blank? (:id person)) ;; "manually invited" statement givers lack the "id"
                   (or (user/get-user-by-email (:email person)) (fail! :error.user-not-found))
                   (user/get-or-create-user-by-email (:email person) inviter))
            statement-id (mongo/create-id)
            statement-giver (assoc person :userId (:id user))]
        (cond-> {:statement {:id            statement-id
                             :person        statement-giver
                             :requested     now
                             :given         nil
                             :reminder-sent nil
                             :metadata      metadata
                             :saateText     saateText
                             :dueDate       dueDate
                             :status        nil}
                 :auth (user/user-in-role user :statementGiver :statementId statement-id)
                 :recipient user}
          (seq metadata) (assoc :metadata metadata))))
    persons))

(defn validate-selected-persons [{{selectedPersons :selectedPersons} :data}]
  (let [non-blank-string-keys (when (some
                                      #(some (fn [k] (or (-> % k string? not) (-> % k ss/blank?))) [:email :name :text])
                                      selectedPersons)
                                (fail :error.missing-parameters))
        has-invalid-email (when (some
                                  #(not (v/email-and-domain-valid? (:email %)))
                                  selectedPersons)
                            (fail :error.email))]
    (or non-blank-string-keys has-invalid-email)))

(defcommand request-for-statement
  {:parameters [functionCode id selectedPersons saateText dueDate]
   :user-roles #{:authority}
   :states #{:open :submitted :complementNeeded}
   :input-validators [(partial action/non-blank-parameters [:saateText :dueDate])
                      (partial action/vector-parameters-with-map-items-with-required-keys [:selectedPersons] [:email :name :text])
                      validate-selected-persons]
   :notified true
   :description "Adds statement-requests to the application and ensures permission to all new users."}
  [{user :user {:keys [organization] :as application} :application now :created :as command}]
  (let [personIdSet (->> selectedPersons (map :id) (filter identity) set)
        manualPersons (filter #(not (:id %)) selectedPersons)]
    (organization/with-organization organization
                                    (fn [{:keys [statementGivers]}]
                                      (let [persons (filter #(personIdSet (:id %)) statementGivers)
                                            persons-combined (concat persons manualPersons)
                                            metadata (when (seq functionCode) (t/metadata-for-document organization functionCode "lausunto"))
                                            details (make-details user now persons-combined metadata saateText dueDate)
                                            statements (map :statement details)
                                            auth (map :auth details)
                                            recipients (map :recipient details)]
                                        (update-application command {$push {:statements {$each statements}
                                                                            :auth {$each auth}}})
                                        (notifications/notify! :request-statement (assoc command :recipients recipients)))))))

(defcommand delete-statement
  {:parameters [id statementId]
   :states     #{:open :submitted :complementNeeded}
   :user-roles #{:authority :applicant}
   :user-authz-roles #{:statementGiver}
   :pre-checks [statement-not-given
                authority-or-statement-owner-applicant]}
  [command]
  (update-application command {$pull {:statements {:id statementId} :auth {:statementId statementId}}}))

(defcommand give-statement
  {:parameters  [:id statementId status text :lang]
   :pre-checks  [statement-exists statement-owner #_statement-not-given]
   :states      #{:open :submitted :complementNeeded}
   :user-roles #{:authority :applicant}
   :user-authz-roles #{:statementGiver}
   :notified    true
   :on-success  [(fn [command _] (notifications/notify! :new-comment command))]
   :description "authrority-roled statement owners can give statements - notifies via comment."}
  [{:keys [application user created lang] :as command}]
  (when-not ((set (possible-statement-statuses application)) status)
    (fail! :error.unknown-statement-status))
  (let [comment-text   (if (statement-given? application statementId)
                         (i18n/loc "statement.updated")
                         (i18n/loc "statement.given"))
        comment-target {:type :statement :id statementId}
        comment-model  (comment/comment-mongo-update (:state application) comment-text comment-target :system false user nil created)]

    (let [response (update-application command
                                       {:statements {$elemMatch {:id statementId}}}
                                       (util/deep-merge
                                         comment-model
                                         {$set {:statements.$.status status
                                                :statements.$.given created
                                                :statements.$.text text}}))
          updated-app (assoc application :statements (map  #(if (= statementId (:id %)) (assoc % :status status :given created :text text) % ) (:statements application) ) )]
                       (child-to-attachment/create-attachment-from-children user updated-app :statements statementId lang)
                       response)))
