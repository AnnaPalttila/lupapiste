(ns lupapalvelu.statement-api
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error fatal]]
            [monger.operators :refer :all]
            [sade.env :as env]
            [sade.util :as util]
            [sade.core :refer :all]
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

(defquery get-organizations-statement-givers
  {:user-roles #{:authorityAdmin}}
  [{user :user}]
  (let [organization (organization/get-organization (user/authority-admins-organization-id user))
        permitPersons (or (:statementGivers organization) [])]
    (ok :data permitPersons)))

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
   :user-roles #{:authority}
   :user-authz-roles action/default-authz-writer-roles
   :states states/all-application-states}
  [{application :application}]
  (let [organization (organization/get-organization (:organization application))
        permitPersons (or (:statementGivers organization) [])]
    (ok :data permitPersons)))

(defquery should-see-unsubmitted-statements
  {:description "Pseudo query for UI authorization logic"
   :parameters [:id]
   :states (states/all-application-states-but [:draft])
   :user-roles #{:authority}
   :user-authz-roles #{:statementGiver}}
  [_])

(notifications/defemail :request-statement
  {:recipients-fn  :recipients
   :subject-key    "statement-request"
   :show-municipality-in-subject true})

(defcommand request-for-statement
  {:parameters [functionCode id personIds]
   :user-roles #{:authority}
   :states #{:open :submitted :complementNeeded}
   :notified true
   :description "Adds statement-requests to the application and ensures permission to all new users."}
  [{user :user {:keys [organization] :as application} :application now :created :as command}]
  (organization/with-organization organization
                                  (fn [{:keys [statementGivers]}]
                                    (let [persons     (filter (comp (set personIds) :id) statementGivers)
                                          users       (map (comp user/get-user-by-email :email) persons)
                                          persons+uid (map #(assoc %1 :userId (:id %2)) persons users)
                                          metadata    (when (seq functionCode) (t/metadata-for-document organization functionCode "lausunto"))
                                          statements  (map (partial create-statement now metadata) persons+uid)
                                          auth        (map #(user/user-in-role %1 :statementGiver :statementId (:id %2)) users statements)]
                                      (when (some nil? users)
                                        (fail! :error.user-not-found :emails (->> (remove :userId persons+uid)
                                                                                  (map :email))))
                                      (update-application command {$push {:statements {$each statements}
                                                                          :auth       {$each auth}}})
                                      (notifications/notify! :request-statement (assoc command :recipients users))))))

(defcommand delete-statement
  {:parameters [id statementId]
   :states     #{:open :submitted :complementNeeded}
   :user-roles #{:authority}
   :pre-checks [statement-not-given]}
  [command]
  (update-application command {$pull {:statements {:id statementId} :auth {:statementId statementId}}}))

(defcommand save-statement-as-draft
  {:parameters       [:id statementId :lang]
   :pre-checks       [statement-exists statement-owner statement-not-given]
   :states           #{:open :submitted :complementNeeded}
   :user-roles       #{:authority}
   :user-authz-roles #{:statementGiver}
   :description "authrority-roled statement owners can save statements as draft before giving final statement."}
  [{application :application {:keys [text status modify-id prev-modify-id]} :data :as command}]
  (when (and status (not ((possible-statement-statuses application) status)))
    (fail! :error.unknown-statement-status))
  (let [statement (-> (util/find-by-id statementId (:statements application))
                      (update-draft text status modify-id prev-modify-id))]
    (update-application command
                        {:statements {$elemMatch {:id statementId}}}
                        {$set {:statements.$ statement}})))

(defcommand give-statement
  {:parameters  [:id statementId status text :lang]
   :pre-checks  [statement-exists statement-owner #_statement-not-given]
   :states      #{:open :submitted :complementNeeded}
   :user-roles #{:authority}
   :user-authz-roles #{:statementGiver}
   :notified    true
   :on-success  [(fn [command _] (notifications/notify! :new-comment command))]
   :description "authrority-roled statement owners can give statements - notifies via comment."}
  [{:keys [application user created lang] {:keys [modify-id prev-modify-id]} :data :as command}]
  (when-not ((possible-statement-statuses application) status)
    (fail! :error.unknown-statement-status))
  (let [comment-text   (if (statement-given? application statementId)
                         (i18n/loc "statement.updated")
                         (i18n/loc "statement.given"))
        comment-target {:type :statement :id statementId}
        comment-model  (comment/comment-mongo-update (:state application) comment-text comment-target :system false user nil created)
        statement   (-> (util/find-by-id statementId (:statements application))
                        (give-statement text status modify-id prev-modify-id))
        response (update-application command
                                     {:statements {$elemMatch {:id statementId}}}
                                     (util/deep-merge
                                      comment-model
                                      {$set {:statements.$ statement}}))
        updated-app (assoc application :statements (util/update-by-id statement (:statements application)))]
    (child-to-attachment/create-attachment-from-children user updated-app :statements statementId lang)
    response))
