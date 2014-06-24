(ns lupapalvelu.comment-api
  (:require [monger.operators :refer :all]
            [sade.env :as env]
            [sade.util :as util]
            [lupapalvelu.core :refer [ok fail fail!]]
            [lupapalvelu.action :refer [defquery defcommand update-application notify] :as action]
            [lupapalvelu.comment :as comment]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.open-inforequest :as open-inforequest]
            [lupapalvelu.user :as user]))

(defn- application-link [lang role full-path]
  (str (env/value :host) "/app/" lang "/" role "#!" full-path))

(defn- create-model [{{id :id info-request? :infoRequest} :application, {target :target} :data} _]
  (let [permit-type-path (if info-request? "/inforequest" "/application")
        [role full-path] (if (= (:type target) "verdict")
                           ["authority" (str "/verdict/" id "/" (:id target))]
                           ["applicant" (str permit-type-path "/" id "/conversation")])]
    {:link-fi (application-link "fi" role full-path)
     :link-sv (application-link "sv" role full-path)}))

(notifications/defemail :new-comment
  {:pred-fn (fn [{user :user {roles :roles target :target} :data}]
              (and
                (not= (:type target) "verdict") ; target might be comment target or attachment target
                (user/authority? user)))
   :model-fn create-model})

(notifications/defemail :application-targeted-comment
  {:recipients-fn  notifications/from-user
   :subject-key    "new-comment"
   :model-fn create-model})

(defn applicant-cant-set-to [{{:keys [to]} :data user :user} _]
  (when (and to (not (user/authority? user)))
    (fail :error.to-settable-only-by-authority)))

(defn- validate-comment-target [{{:keys [target]} :data}]
  (when (string? target)
    (fail :error.unknown-type)))

(defquery can-target-comment-to-authority
  {:roles [:authority]
   :pre-checks  [open-inforequest/not-open-inforequest-user-validator]
   :description "Dummy command for UI logic"})

(defcommand add-comment
  {:parameters [id text target roles]
   :roles      [:applicant :authority]
   :extra-auth-roles [:statementGiver]
   :pre-checks [applicant-cant-set-to]
   :input-validators [validate-comment-target
                      (partial action/map-parameters [:target])
                      (partial action/vector-parameters [:roles])]
   :notified   true
   :on-success [(notify :new-comment)
                (fn [{data :data :as command} _]
                  (when-let [to-user (and (:to data) (user/get-user-by-id (:to data)))]
                    ;; LUPA-407
                    (notifications/notify! :application-targeted-comment (assoc command :user to-user))))
                open-inforequest/notify-on-comment]}
  [{{:keys [to mark-answered openApplication] :or {mark-answered true}} :data :keys [user created application] :as command}]
  (let [to-user   (and to (or (user/get-user-by-id to) (fail! :to-is-not-id-of-any-user-in-system)))
        ensured-visibility (if (seq roles)
                             (remove nil? (conj (set roles) (:role user) (:role to-user)))
                             #{:authority :applicant})]
    (update-application command
      (util/deep-merge
        (comment/comment-mongo-update (:state application) text target (:role user) mark-answered user to-user created ensured-visibility)
        (when openApplication {$set {:state :open, :opened created}})))))
