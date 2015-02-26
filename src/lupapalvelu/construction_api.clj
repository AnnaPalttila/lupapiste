(ns lupapalvelu.construction-api
  (:require [monger.operators :refer [$set $elemMatch]]
            [lupapalvelu.action :refer [defcommand update-application notify] :as action]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.xml.krysp.application-as-krysp-to-backing-system :as mapping-to-krysp]
            [sade.core :refer :all]
            [sade.util :as util]))


;;
;; Inform construction started & ready
;;

(defcommand inform-construction-started
  {:parameters ["id" startedTimestampStr]
   :roles      [:applicant :authority]
   :states     [:verdictGiven]
   :notified   true
   :on-success (notify :application-state-change)
   :pre-checks [(permit/validate-permit-type-is permit/YA)]
   :input-validators [(partial action/non-blank-parameters [:startedTimestampStr])]}
  [{:keys [user created] :as command}]
  (let [timestamp (util/to-millis-from-local-date-string startedTimestampStr)]
    (update-application command {$set {:modified created
                                       :started timestamp
                                       :startedBy (select-keys user [:id :firstName :lastName])
                                       :state  :constructionStarted}}))
  (ok))

(defcommand inform-building-construction-started
  {:parameters ["id" buildingIndex startedDate lang]
   :roles      [:NONE] ;FIXME rakentamisen aikaisen toimminan yhteydessa korjataan oikeae
   :states     [:verdictGiven :constructionStarted]
   :notified   true
   :pre-checks [(permit/validate-permit-type-is permit/R)]
   :input-validators [(partial action/non-blank-parameters [:buildingIndex :startedDate :lang])]}
  [{:keys [user created application] :as command}]
  (let [timestamp     (util/to-millis-from-local-date-string startedDate)
        app-updates   (merge
                        {:modified created}
                        (when (= "verdictGiven" (:state application))
                          {:started created
                           :state  :constructionStarted}))
        application   (merge application app-updates)
        organization  (organization/get-organization (:organization application))
        ftp-user?     (organization/has-ftp-user? organization (permit/permit-type application))
        building      (or
                        (some #(when (= (str buildingIndex) (:index %)) %) (:buildings application))
                        (fail! :error.unknown-building))]
    (when ftp-user?
      (mapping-to-krysp/save-aloitusilmoitus-as-krysp application lang organization timestamp building user))
    (update-application command
      {:buildings {$elemMatch {:index (:index building)}}}
      {$set (merge app-updates {:buildings.$.constructionStarted timestamp
                                :buildings.$.startedBy (select-keys user [:id :firstName :lastName])})})
    (when (= "verdictGiven" (:state application))
      (notifications/notify! :application-state-change command))
    (ok :integrationAvailable ftp-user?)))

(defcommand inform-construction-ready
  {:parameters ["id" readyTimestampStr lang]
   :roles      [:applicant :authority]
   :states     [:constructionStarted]
   :on-success (notify :application-state-change)
   :pre-checks [(permit/validate-permit-type-is permit/YA)]
   :input-validators [(partial action/non-blank-parameters [:readyTimestampStr])]}
  [{:keys [user created application] :as command}]
  (let [timestamp     (util/to-millis-from-local-date-string readyTimestampStr)
        app-updates   {:modified created
                       :closed timestamp
                       :closedBy (select-keys user [:id :firstName :lastName])
                       :state :closed}
        application   (merge application app-updates)
        organization  (organization/get-organization (:organization application))
        ftp-user?     (organization/has-ftp-user? organization (permit/permit-type application))]
    (when ftp-user?
      (mapping-to-krysp/save-application-as-krysp application lang application organization))
    (update-application command {$set app-updates})
    (ok :integrationAvailable ftp-user?)))
