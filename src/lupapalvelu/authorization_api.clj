(ns lupapalvelu.authorization-api
  "API for manipulating application.auth"
  (:require [clojure.string :refer [blank? join trim split]]
            [swiss.arrows :refer [-<>>]]
            [monger.operators :refer :all]
            [sade.strings :as ss]
            [sade.core :refer [ok fail fail! unauthorized]]
            [sade.util :as util]
            [lupapalvelu.action :refer [defquery defcommand defraw update-application all-application-states notify] :as action]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.user-api :as user-api]
            [lupapalvelu.user :as user]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.document.commands :as commands]))

;;
;; Invites
;;

(defquery invites
  {:user-roles #{:applicant :authority :oirAuthority}}
  [{{:keys [id]} :user}]
  (let [common     {:auth {$elemMatch {:invite.user.id id}}}
        query      {$and [common {:state {$ne :canceled}}]}
        data       (mongo/select :applications query [:auth :operations :address :municipality])
        invites    (filter #(= id (get-in % [:user :id])) (map :invite (mapcat :auth data)))
        invites-with-application (map
                                   #(update-in % [:application]
                                               (fn [app-id]
                                                 (select-keys
                                                   (util/find-by-id app-id data)
                                                   [:id :address :operations :municipality])))
                                   invites)]
    (ok :invites invites-with-application)))

(defn- create-invite-email-model [command conf recipient]
  (assoc (notifications/create-app-model command conf recipient)
    :message (get-in command [:data :text])
    :recipient-email (:email recipient)))

(notifications/defemail :invite  {:recipients-fn :recipients
                                  :model-fn create-invite-email-model})

(defn- valid-role [role]
  (#{:writer :foreman} (keyword role)))

(defn- create-invite-auth [inviter invited application-id text document-name document-id path role timestamp]
  (let [invite {:application  application-id
                :text         text
                :path         path
                :documentName document-name
                :documentId   document-id
                :created      timestamp
                :email        (:email invited)
                :role         role
                :user         (user/summary invited)
                :inviter      (user/summary inviter)}]
    (assoc (user/user-in-role invited :reader) :invite invite)))

(defn send-invite! [{{:keys [email text documentName documentId path role]} :data
                     timestamp :created
                     inviter :user
                     application :application
                     :as command}]
  {:pre [(valid-role role)]}
  (let [email (user/canonize-email email)
        existing-user (user/get-user-by-email email)]
    (if (or (domain/invite application email) (domain/has-auth? application (:id existing-user)))
      (fail :invite.already-has-auth)
      (let [invited (user-api/get-or-create-user-by-email email inviter)
            auth    (create-invite-auth inviter invited (:id application) text documentName documentId path role timestamp)]
        (update-application command
          {:auth {$not {$elemMatch {:invite.user.username (:email invited)}}}}
          {$push {:auth     auth}
           $set  {:modified timestamp}})
        (notifications/notify! :invite (assoc command :recipients [invited]))
        (ok)))))

(defn- role-validator [{{role :role} :data}]
  (when-not (valid-role role)
    (fail! :error.illegal-role :parameters role)))

(defcommand invite-with-role
  {:parameters [:id :email :text :documentName :documentId :path :role]
   :input-validators [(partial action/non-blank-parameters [:email])
                      action/email-validator
                      role-validator]
   :states     (action/all-application-states-but [:closed :canceled])
   :user-roles #{:applicant :authority}
   :notified   true}
  [command]
  (send-invite! command))

(defcommand approve-invite
  {:parameters [id]
   :user-roles #{:applicant}
   :user-authz-roles action/default-authz-reader-roles
   :states     action/all-application-states}
  [{:keys [created user application] :as command}]
  (when-let [my-invite (domain/invite application (:email user))]

    (let [role (or (:role my-invite) (:role (domain/get-auth application (:id user))))]
      (update-application command
        {:auth {$elemMatch {:invite.user.id (:id user)}}}
        {$set {:modified created
               :auth.$   (assoc (user/user-in-role user role) :inviteAccepted created)}}))

    (when-not (empty? (:documentId my-invite))
      (when-let [document (domain/get-document-by-id application (:documentId my-invite))]
        ; Document can be undefined (invite's documentId is an empty string) in invite or removed by the time invite is approved.
        ; It's not possible to combine Mongo writes here, because only the last $elemMatch counts.
        (commands/do-set-user-to-document (domain/get-application-as id user :include-canceled-apps? true) document (:id user) (:path my-invite) created)))))

(defn generate-remove-invalid-user-from-docs-updates [{docs :documents :as application}]
  (-<>> docs
    (map-indexed
      (fn [i doc]
        (->> (model/validate application doc)
          (filter #(= (:result %) [:err "application-does-not-have-given-auth"]))
          (map (comp (partial map name) :path))
          (map (comp (partial join ".") (partial concat ["documents" i "data"]))))))
    flatten
    (zipmap <> (repeat ""))))

(defn- do-remove-auth [{application :application :as command} username]
  (let [username (user/canonize-email username)
        user-pred #(when (and (= (:username %) username) (not= (:type %) "owner")) %)]
    (when (some user-pred (:auth application))
      (let [updated-app (update-in application [:auth] (fn [a] (remove user-pred a)))
            doc-updates (generate-remove-invalid-user-from-docs-updates updated-app)]
        (update-application command
          (merge
            {$pull {:auth {$and [{:username username}, {:type {$ne :owner}}]}}
             $set  {:modified (:created command)}}
            (when (seq doc-updates) {$unset doc-updates})))))))

(defcommand decline-invitation
  {:parameters [:id]
   :user-roles #{:applicant :authority}
   :user-authz-roles action/default-authz-reader-roles
   :states     action/all-application-states}
  [command]
  (do-remove-auth command (get-in command [:user :username])))

;;
;; Auhtorizations
;;

(defcommand remove-auth
  {:parameters [:id username]
   :input-validators [(partial action/non-blank-parameters [:username])]
   :user-roles #{:applicant :authority}
   :states     (action/all-application-states-but [:canceled])}
  [command]
  (do-remove-auth command username))

(defn- manage-unsubscription [{application :application user :user :as command} unsubscribe?]
  (let [username (get-in command [:data :username])]
    (if (or (= username (:username user))
         (some (partial = (:organization application)) (user/organization-ids-by-roles user #{:authority})))
      (update-application command
        {:auth {$elemMatch {:username username}}}
        {$set {:auth.$.unsubscribed unsubscribe?}})
      unauthorized)))

(defcommand unsubscribe-notifications
  {:parameters [:id :username]
   :user-roles #{:applicant :authority}
   :states all-application-states}
  [command]
  (manage-unsubscription command true))

(defcommand subscribe-notifications
  {:parameters [:id :username]
   :user-roles #{:applicant :authority}
   :states all-application-states}
  [command]
  (manage-unsubscription command false))
