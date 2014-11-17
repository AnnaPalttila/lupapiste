(ns lupapalvelu.company-api
  (:require [sade.core :refer [ok fail fail! unauthorized unauthorized!]]
            [lupapalvelu.action :refer [defquery defcommand] :as action]
            [lupapalvelu.company :as c]
            [lupapalvelu.user :as u]))

;;
;; Company API:
;;

; Validator: check is user is either :admin or user belongs to requested company

(defn validate-user-is-admin-or-company-member [{{:keys [role company]} :user {requested-company :company} :data}]
  (if-not (or (= role "admin")
              (= (:id company) requested-company))
    unauthorized))

(defn validate-user-is-admin-or-company-admin [{user :user}]
  (if-not (or (= (get user :role) "admin")
              (= (get-in user [:company :role]) "admin"))
    unauthorized))

;;
;; Basic API:
;;

(defquery company
  {:roles [:anonymous]
   :input-validators [validate-user-is-admin-or-company-member]
   :parameters [company]}
  [{{:keys [users]} :data}]
  (ok :company (c/find-company! {:id company})
      :users   (and users (c/find-company-users company))))

(defquery companies
  {:roles [:anonymous]}
  [_]
  (ok :companies (c/find-companies)))

(defcommand company-update
  {:roles [:anonymous]
   :input-validators [validate-user-is-admin-or-company-member]
   :parameters [company updates]}
  (ok :company (c/update-company! company updates)))

(defcommand company-user-update
  {:roles [:anonymous]
   :parameters [user-id op value]}
  [{caller :user}]
  (let [target-user (u/get-user-by-id! user-id)]
    (if-not (or (= (:role caller) "admin")
                (and (= (get-in caller [:company :role])
                        "admin")
                     (= (get-in caller [:company :id])
                        (get-in target-user [:company :id]))))
      (unauthorized!))
    (c/update-user! user-id (keyword op) value)
    (ok)))

(defquery company-invite-user
  {:roles [:anonymous]
   :input-validators [validate-user-is-admin-or-company-admin]
   :parameters [email]}
  [{caller :user}]
  (let [user (u/find-user {:email email})]
    (cond
      (nil? user)
      (ok :result :not-found)

      (get-in user [:company :id])
      (ok :result :already-in-company)

      :else
      (do
        (c/invite-user! email (-> caller :company :id))
        (ok :result :invited)))))

(defcommand company-add-user
  {:roles [:anonymous]
   :parameters [firstName lastName email]}
  [{user :user {:keys [admin]} :params}]
  (if-not (or (= (:role user) "admin")
              (= (get-in user [:company :role]) "admin"))
    (fail! :forbidden))
  (c/add-user! {:firstName firstName :lastName lastName :email email}
               (c/find-company-by-id (-> user :company :id))
               (if admin :admin :user))
  (ok))

(defcommand company-invite
  {:parameters [id company-id]
   :states (action/all-application-states-but [:closed :canceled])
   :roles [:applicant :authority]}
  [{caller :user application :application}]
  (c/company-invite caller application company-id)
  (ok))
