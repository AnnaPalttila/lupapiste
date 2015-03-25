(ns lupapalvelu.batchrun
  (:require [taoensso.timbre :refer [error]]
            [me.raynes.fs :as fs]
            [clojure.java.io :as io]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.neighbors :as neighbors]
            [lupapalvelu.open-inforequest :as inforequest]
            [clj-time.core :refer [days weeks months ago]]
            [clj-time.coerce :refer [to-long]]
            [monger.operators :refer :all]
            [clojure.string :as s]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.user :as user]
            [lupapalvelu.logging :as logging]
            [lupapalvelu.verdict-api :as verdict-api]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.xml.krysp.reader :as krysp]
            [lupapalvelu.xml.asianhallinta.verdict :as ah-verdict]
            [lupapalvelu.action :refer :all]
            [sade.util :as util]
            [sade.env :as env]
            [sade.dummy-email-server]
            [sade.core :refer :all]))


(defn get-timestamp-from-now [time-key amount]
  {:pre [(#{:day :week :month} time-key)]}
  (let [time-fn (case time-key
                  :day days
                  :week weeks
                  :month months)]
    (to-long (-> amount time-fn ago))))

(defn- older-than [timestamp] {$lt timestamp})

(defn- get-app-owner [application]
  (let [owner (domain/get-auths-by-role application :owner)]
    (user/get-user-by-id (-> owner first :id))))


;; Email definition for the "open info request reminder"

(defn- oir-reminder-base-email-model [{{token :token-id created-date :created-date} :data} _ recipient]
  (let  [link-fn (fn [lang] (str (env/value :host) "/api/raw/openinforequest?token-id=" token "&lang=" (name lang)))
         info-fn (fn [lang] (env/value :oir :wanna-join-url))]
    {:link-fi (link-fn :fi)
     :link-sv (link-fn :sv)
     :created-date created-date}))

(def- oir-reminder-email-conf
  {:recipients-fn  notifications/from-data
   :subject-key    "open-inforequest-reminder"
   :model-fn       oir-reminder-base-email-model
   :application-fn (fn [{id :id}] (mongo/by-id :applications id))})

(notifications/defemail :reminder-open-inforequest oir-reminder-email-conf)

;; Email definition for the "Neighbor reminder"

(notifications/defemail :reminder-neighbor (assoc neighbors/email-conf :subject-key "neighbor-reminder"))

(defn- request-statement-reminder-email-model [{{created-date :created-date} :data application :application :as command} _ recipient]
  {:link-fi (notifications/get-application-link application nil "fi" recipient)
   :link-sv (notifications/get-application-link application nil "sv" recipient)
   :created-date created-date})

(notifications/defemail :reminder-request-statement
  {:recipients-fn  :recipients
   :subject-key    "statement-request-reminder"
   :model-fn       request-statement-reminder-email-model})

;; "Lausuntopyynto: Pyyntoon ei ole vastattu viikon kuluessa ja hakemuksen tila on valmisteilla tai vireilla. Lahetetaan viikoittain uudelleen."
(defn statement-request-reminder []
  (let [timestamp-1-week-ago (get-timestamp-from-now :week 1)
        apps (mongo/select :applications {:state {$in ["open" "submitted"]}
                                          :statements {$elemMatch {:requested (older-than timestamp-1-week-ago)
                                                                   :given nil
                                                                   $or [{:reminder-sent {$exists false}}
                                                                        {:reminder-sent nil}
                                                                        {:reminder-sent (older-than timestamp-1-week-ago)}]}}})]
    (doseq [app apps
            statement (:statements app)
            :let [requested (:requested statement)]
            :when (and
                    (nil? (:given statement))
                    (< requested timestamp-1-week-ago))]
      (notifications/notify! :reminder-request-statement {:application app
                                                         :recipients [(user/get-user-by-email (get-in statement [:person :email]))]
                                                          :data {:created-date (util/to-local-date requested)}})
      (update-application (application->command app)
        {:statements {$elemMatch {:id (:id statement)}}}
        {$set {:statements.$.reminder-sent (now)}}))))


;; "Neuvontapyynto: Neuvontapyyntoon ei ole vastattu viikon kuluessa eli neuvontapyynnon tila on avoin. Lahetetaan viikoittain uudelleen."
(defn open-inforequest-reminder []
  (let [timestamp-1-week-ago (get-timestamp-from-now :week 1)
        oirs (mongo/select :open-inforequest-token {:created (older-than timestamp-1-week-ago)
                                                    :last-used nil
                                                    $or [{:reminder-sent {$exists false}}
                                                         {:reminder-sent nil}
                                                         {:reminder-sent (older-than timestamp-1-week-ago)}]})]
    (doseq [oir oirs]
      (let [application (mongo/by-id :applications (:application-id oir))]
        (when (= "info" (:state application))
          (notifications/notify! :reminder-open-inforequest {:application application
                                                             :data {:email (:email oir)
                                                                    :token-id (:id oir)
                                                                    :created-date (util/to-local-date (:created oir))}})
          (mongo/update-by-id :open-inforequest-token (:id oir) {$set {:reminder-sent (now)}})
          )))))


;; "Naapurin kuuleminen: Kuulemisen tila on "Sahkoposti lahetetty", eika allekirjoitusta ole tehty viikon kuluessa ja hakemuksen tila on valmisteilla tai vireilla. Muistutus lahetetaan kerran."
(defn neighbor-reminder []
  (let [timestamp-1-week-ago (get-timestamp-from-now :week 1)
        apps (mongo/select :applications {:state {$in ["open" "submitted"]}
                                          :neighbors.status {$elemMatch {$and [{:state {$in ["email-sent"]}}
                                                                               {:created (older-than timestamp-1-week-ago)}
                                                                               ]}}})]
    (doseq [app apps
            neighbor (:neighbors app)
            :let [statuses (:status neighbor)]]

      (when (not-any? #(or
                         (= "reminder-sent" (:state %))
                         (= "response-given-ok" (:state %))
                         (= "response-given-comments" (:state %))) statuses)

        (doseq [status statuses]

          (when (and
                  (= "email-sent" (:state status))
                  (< (:created status) timestamp-1-week-ago))
            (notifications/notify! :reminder-neighbor {:application app
                                                       :data {:email (:email status)
                                                              :token (:token status)
                                                              :neighborId (:id neighbor)}})
            (update-application (application->command app)
              {:neighbors {$elemMatch {:id (:id neighbor)}}}
              {$push {:neighbors.$.status {:state    "reminder-sent"
                                           :token    (:token status)
                                           :created  (now)}}})))))))


(notifications/defemail :reminder-application-state
  {:subject-key    "active-application-reminder"
   :recipients-fn  notifications/from-user})

;; "Hakemus: Hakemuksen tila on valmisteilla tai vireilla, mutta edellisesta paivityksesta on aikaa yli kuukausi. Lahetetaan kuukausittain uudelleen."
(defn application-state-reminder []
  (let [timestamp-1-month-ago (get-timestamp-from-now :month 1)
        apps (mongo/select :applications {:state {$in ["open" "submitted"]}
                                          :modified (older-than timestamp-1-month-ago)
                                          $or [{:reminder-sent {$exists false}}
                                               {:reminder-sent nil}
                                               {:reminder-sent (older-than timestamp-1-month-ago)}]})]
    (doseq [app apps]
      (notifications/notify! :reminder-application-state {:application app
                                                          :user (get-app-owner app)})
      (update-application (application->command app)
        {$set {:reminder-sent (now)}}))))


(defn send-reminder-emails [& args]
  (when (env/feature? :reminders)
    (mongo/connect!)
    (statement-request-reminder)
    (open-inforequest-reminder)
    (neighbor-reminder)
    (application-state-reminder)

    (mongo/disconnect!)))

(defn fetch-verdicts []
  (let [orgs-with-wfs-url-defined-for-some-scope (organization/get-organizations
                                                   {$or [{:krysp.R.url {$exists true}}
                                                         {:krysp.YA.url {$exists true}}
                                                         {:krysp.P.url {$exists true}}
                                                         {:krysp.MAL.url {$exists true}}
                                                         {:krysp.VVVL.url {$exists true}}
                                                         {:krysp.YI.url {$exists true}}
                                                         {:krysp.YL.url {$exists true}}]}
                                                   {:krysp 1})
        orgs-by-id (reduce #(assoc %1 (:id %2) (:krysp %2)) {} orgs-with-wfs-url-defined-for-some-scope)
        org-ids (keys orgs-by-id)
        apps (mongo/select :applications {:state {$in ["sent"]} :organization {$in org-ids}})
        eraajo-user {:id "-"
                     :enabled true
                     :lastName "Er\u00e4ajo"
                     :firstName "Lupapiste"
                     :role "authority"
                     :organizations org-ids}]
    (doall
      (pmap
        (fn [{:keys [id permitType organization] :as app}]

          (try
            (let [url (get-in orgs-by-id [organization (keyword permitType) :url])]
              (logging/with-logging-context {:applicationId id}
                (if-not (s/blank? url)

                  (let [command (assoc (application->command app) :user eraajo-user :created (now))
                        result (verdict-api/do-check-for-verdict command)]
                    (when (-> result :verdicts count pos?)
                      ;; Print manually to events.log, because "normal" prints would be sent as emails to us.
                      (logging/log-event :info {:run-by "Automatic verdicts checking" :event "Found new verdict"})
                      (notifications/notify! :application-verdict command)))

                  (logging/log-event :info {:run-by "Automatic verdicts checking"
                                            :event "No Krysp WFS url defined for organization"
                                            :organization {:id organization :permit-type permitType}}))))
            (catch Exception e (error e))))
        apps))))

(defn check-for-verdicts [& args]
  (when (env/feature? :automatic-verdicts-checking)
    (mongo/connect!)
    (fetch-verdicts)
    (mongo/disconnect!)))

(defn- get-asianhallinta-ftp-users [organizations]
  (remove
    nil?
    (for [org organizations
          scope (:scope org)]
      (get-in scope [:caseManagement :ftpUser]))))

(defn fetch-asianhallinta-verdicts []
  (let [ah-organizations (mongo/select :organizations
                                       {"scope.caseManagement.ftpUser" {$exists true}}
                                       {"scope.caseManagement.ftpUser" 1})
        ftp-users (get-asianhallinta-ftp-users ah-organizations)]
    (doseq [user ftp-users
            :let [path (str
                         (env/value :outgoing-directory) "/"
                         user "/"
                         "asianhallinta/to_lupapiste/")]
            zip (filter
                  #(re-matches #".+\.zip$" (.getName %))
                  (-> path io/file (.listFiles) seq))]

      (fs/mkdirs (str path "archive"))
      (fs/mkdirs (str path "error"))
      (let [result {:ok true} #_(ah-verdict/process-ah-verdict (.getPath zip) user)]
        (if (ok? result)
          (fs/rename zip (io/file (str path "archive/" (.getName zip))))
          (fs/rename zip (io/file (str path "error/" (.getName zip)))))))))

(defn check-for-asianhallinta-verdicts []
  (when (env/feature? :automatic-verdicts-checking)
    (mongo/connect!)
    (fetch-asianhallinta-verdicts)
    (mongo/disconnect!)))
