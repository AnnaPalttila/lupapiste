(ns lupapalvelu.statement-api
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error fatal]]
            [monger.operators :refer :all]
            [sade.env :as env]
            [sade.strings :as ss]
            [sade.util :as util]
            [sade.core :refer :all]
            [lupapalvelu.action :refer [defquery defcommand update-application executed] :as action]
            [lupapalvelu.mongo :refer [$each] :as mongo]
            [lupapalvelu.user :refer [with-user-by-email] :as user]
            [lupapalvelu.user-api :as user-api]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.comment :as comment]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.statement :refer :all]))

;;
;; Authority Admin operations
;;

(defquery get-organizations-statement-givers
  {:roles [:authorityAdmin]}
  [{{:keys [organizations]} :user}]
  (let [organization (organization/get-organization (first organizations))
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
   :roles      [:authorityAdmin]}
  [{{:keys [organizations]} :user}]
  (let [organization-id (first organizations)
        organization    (organization/get-organization organization-id)
        email           (ss/lower-case email)
        statement-giver-id (mongo/create-id)]
    (if-let [user (user/get-user-by-email email)]
      (do
        (when-not (user/authority? user) (fail! :error.not-authority))
        (organization/update-organization organization-id {$push {:statementGivers {:id statement-giver-id
                                                                                    :text text
                                                                                    :email email
                                                                                    :name (str (:firstName user) " " (:lastName user))}}})
        (notifications/notify! :add-statement-giver  {:user user :data {:text text :organization organization}})
        (ok :id statement-giver-id))
      (fail :error.user-not-found))))

(defcommand delete-statement-giver
  {:parameters [personId]
   :roles      [:authorityAdmin]}
  [{{:keys [organizations]} :user}]
  (organization/update-organization (first organizations) {$pull {:statementGivers {:id personId}}}))

;;
;; Authority operations
;;

(defquery get-statement-givers
  {:parameters [:id]
   :roles [:authority]
   :states action/all-application-states}
  [{application :application}]
  (let [organization (organization/get-organization (:organization application))
        permitPersons (or (:statementGivers organization) [])]
    (ok :data permitPersons)))

(defcommand should-see-unsubmitted-statements
  {:description "Pseudo command for UI authorization logic"
   :roles [:authority] :extra-auth-roles [:statementGiver]} [_])

(notifications/defemail :request-statement
  {:recipients-fn  :recipients
   :subject-key    "statement-request"
   :show-municipality-in-subject true})

(defcommand request-for-statement
  {:parameters  [id personIds]
   :roles       [:authority]
   :states      [:draft :open :submitted :complement-needed]
   :notified    true
   :description "Adds statement-requests to the application and ensures permission to all new users."}
  [{user :user {:keys [organization] :as application} :application now :created :as command}]
  (organization/with-organization organization
    (fn [{:keys [statementGivers]}]
      (let [personIdSet (set personIds)
            persons     (filter #(personIdSet (:id %)) statementGivers)
            details     (map #(let [user (or (user/get-user-by-email (:email %)) (fail! :error.not-found))
                                    statement-id (mongo/create-id)
                                    statement-giver (assoc % :userId (:id user))]
                                {:statement {:id statement-id
                                             :person    statement-giver
                                             :requested now
                                             :given     nil
                                             :reminder-sent nil
                                             :status    nil}
                                 :auth (user/user-in-role user :statementGiver :statementId statement-id)
                                 :recipient user}) persons)
            statements (map :statement details)
            auth       (map :auth details)
            recipients (map :recipient details)]
          (update-application command {$push {:statements {$each statements}
                                              :auth {$each auth}}})
          (notifications/notify! :request-statement (assoc command :recipients recipients))))))

(defcommand delete-statement
  {:parameters [id statementId]
   :states     [:draft :open :submitted :complement-needed]
   :roles      [:authority]}
  [command]
  (update-application command {$pull {:statements {:id statementId} :auth {:statementId statementId}}}))

(defcommand give-statement
  {:parameters  [id statementId status text :lang]
   :pre-checks  [statement-exists statement-owner #_statement-not-given]
   :input-validators [(fn [{{status :status} :data}] (when-not (#{"yes", "no", "condition"} status) (fail :error.missing-parameters)))]
   :states      [:draft :open :submitted :complement-needed]
   :roles       [:authority]
   :extra-auth-roles [:statementGiver]
   :notified    true
   :on-success  [(fn [command _] (notifications/notify! :new-comment command))]
   :description "authrority-roled statement owners can give statements - notifies via comment."}
  [{:keys [application user created] :as command}]
  (let [comment-text   (if (statement-given? application statementId)
                         (i18n/loc "statement.updated")
                         (i18n/loc "statement.given"))
        comment-target {:type :statement :id statementId}
        comment-model  (comment/comment-mongo-update (:state application) comment-text comment-target :system false user nil created)]
    (update-application command
      {:statements {$elemMatch {:id statementId}}}
      (util/deep-merge
        comment-model
        {$set {:statements.$.status status
               :statements.$.given created
               :statements.$.text text}}))))
