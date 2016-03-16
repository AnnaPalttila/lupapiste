(ns lupapalvelu.admin-api
  (:require [taoensso.timbre :as timbre :refer [trace tracef debug info infof warn warnf error errorf]]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.action :refer [defraw] :as action]
            [sade.core :refer [now]]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.xml.krysp.application-from-krysp :as krysp-fetch]
            [lupapalvelu.xml.krysp.building-reader :as building-reader]))

(defraw admin-download-application-xml
  {:parameters [applicationId]
   :input-validators [(partial action/non-blank-parameters [:applicationId])]
   :user-roles #{:admin}}
  [_]
  (if-let [application (domain/get-application-no-access-checking applicationId)]
    {:status 200
     :body (krysp-fetch/get-application-xml-by-application-id application true)
     :headers {"Content-Type" "application/xml;charset=UTF-8"
               "Content-Disposition" (format "attachment;filename=\"%s-%s.xml\"" applicationId (now))
               "Cache-Control" "no-cache"}}
    {:status 404
     :headers {"Content-Type"  "text/plain" "Cache-Control" "no-cache"}
     :body "Application not found"}))

(defraw admin-download-application-xml-with-kuntalupatunnus
  {:parameters [kuntalupatunnus municipality permitType]
   :input-validators [(partial action/non-blank-parameters [:kuntalupatunnus :municipality :permitType])]
   :user-roles #{:admin}}
  [_]
  (if-let [organization (organization/resolve-organization municipality permitType)]  ;; this also validates the permit-type
    (let [dummy-application {:id "" :permitType permitType :organization (:id organization)}]
      {:status 200
       :body (krysp-fetch/get-application-xml-by-backend-id dummy-application kuntalupatunnus true)
       :headers {"Content-Type" "application/xml;charset=UTF-8"
                 "Content-Disposition" (format "attachment;filename=\"%s-%s-%s.xml\"" municipality permitType (now))
                 "Cache-Control" "no-cache"}})
    {:status 404
     :headers {"Content-Type"  "text/plain" "Cache-Control" "no-cache"}
     :body "Organization not found"}))

(defraw admin-download-authority-usernames
  {:user-roles #{:admin}}
  [_]
  {:status 200
   :body (clojure.string/join "\n" (mongo/distinct :users :username {:role "authority"}))
   :headers {"Content-Type" "text/plain"
             "Cache-Control" "no-cache"}})

(defraw admin-download-building-info
  {:parameters [applicationId]
   :input-validators [(partial action/non-blank-parameters [:applicationId])]
   :user-roles #{:admin}}
  [_]
    (if-let [application (mongo/by-id :applications applicationId ["organization" "permitType" "propertyId"])]
      (let [{url :url credentials :credentials} (organization/get-krysp-wfs application)]
        {:status  200
         :body    (building-reader/building-xml url credentials (:propertyId application) true)
         :headers {"Content-Type"        "application/xml;charset=UTF-8"
                   "Content-Disposition" (format "attachment;filename=\"%s-%s-%s.xml\"" applicationId (:propertyId application) (now))
                   "Cache-Control"       "no-cache"}})
      {:status 404
       :headers {"Content-Type"  "text/plain" "Cache-Control" "no-cache"}
       :body "Application not found"}))
