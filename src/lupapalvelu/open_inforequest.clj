(ns lupapalvelu.open-inforequest
  (:require [taoensso.timbre :as timbre :refer [infof info]]
            [monger.operators :refer :all]
            [noir.session :as session]
            [noir.response :as resp]
            [lupapalvelu.core :refer [now fail!]]
            [lupapalvelu.action :refer [defraw]]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.security :refer [random-password]]
            [lupapalvelu.notifications :as notifications]))

(defn new-open-inforequest! [{application-id :id organization-id :organization} host]
  (assert application-id)
  (assert organization-id)
  (let [organization    (organization/get-organization organization-id)
        email           (:open-inforequest-email organization)
        token-id        (random-password 48)]
    (when-not organization (fail! :error.unknown-organization))
    (when-not email (fail! :error.missing-organization-email))
    (infof "open-inforequest: new-inforequest: application=%s, organization=%s, email=%s, token=%s" application-id organization-id email token-id)
    (mongo/insert :open-inforequest-token {:id               token-id
                                           :application-id   application-id
                                           :organization-id  organization-id
                                           :email            email
                                           :created          (now)
                                           :last-used        nil})
    (notifications/send-open-inforequest-invite! email token-id application-id host)
    true))

(defn make-user [token]
  (let [organization-id (:organization-id token)
        email (:email token)]
    {:id (str "oir-" organization-id "-" email)
     :email email
     :enabled true
     :role :authority
     :oir true
     :organizations [organization-id]
     :firstName ""
     :lastName email
     :phone ""
     :username email}))

(defraw openinforequest
  [{{token-id :token-id} :data lang :lang {:keys [host]} :web}]
  (info "open-inforequest: open:" token-id lang)
  (let [token (mongo/by-id :open-inforequest-token token-id)
        url   (str host "/app/" (name lang) "/oir#!/inforequest/" (:application-id token))]
    (when-not token (fail! :error.unknown-open-inforequest-token))
    (mongo/update-by-id :open-inforequest-token token-id {$set {:last-used (now)}})
    (session/put! :user (make-user token))
    (resp/redirect url)))
