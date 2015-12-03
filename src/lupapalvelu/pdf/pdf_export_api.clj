(ns lupapalvelu.pdf.pdf-export-api
  (:require [lupapalvelu.action :refer [defraw]]
            [lupapalvelu.application :as a]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.pdf.pdf-export :as pdf-export]
            [lupapalvelu.states :as states]))

(defraw pdf-export
  {:parameters [:id]
   :user-roles #{:applicant :authority :oirAuthority}
   :user-authz-roles auth/all-authz-roles
   :org-authz-roles  auth/all-org-authz-roles
   :states     states/all-states}
  [{:keys [user application lang]}]
  (if application
    {:status 200
     :headers {"Content-Type" "application/pdf"}
     :body (-> application (a/with-masked-person-ids user) (pdf-export/generate lang))}
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "404"}))
