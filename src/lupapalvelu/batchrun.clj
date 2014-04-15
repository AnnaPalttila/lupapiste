(ns lupapalvelu.batchrun
  (:require [lupapalvelu.notifications :as notifications]
            [lupapalvelu.neighbors :as neighbors]
            [lupapalvelu.open-inforequest :as inforequest]
            [clj-time.core :refer [weeks months ago]]
            [clj-time.coerce :refer [to-long]]
            [monger.operators :refer :all]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.core :refer [now]]
            [lupapalvelu.user :as user]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.action :refer :all]
            [sade.util :as util]
            [sade.env :as env]))


(defn- get-timestamp-from-now [time-key amount]
  {:pre [(#{:week :month} time-key)]}
  (let [time-fn (case time-key
                  :week weeks
                  :month months)]
    (to-long (-> amount time-fn ago))))

(defn- older-than [timestamp] {$lt timestamp})

(defn- get-app-owner-email [application]
  (let [owner (domain/get-auths-by-role application :owner)
        owner-id (-> owner first :id)
        user (user/get-user-by-id owner-id)]
    (:email user)))


;; For the "open info request reminder"

(defn- oir-reminder-base-email-model [{{token :token-id created-date :created-date} :data} _]
  (let  [link-fn (fn [lang] (str (env/value :host) "/api/raw/openinforequest?token-id=" token "&lang=" (name lang)))
         info-fn (fn [lang] (env/value :oir :wanna-join-url))]
    {:link-fi (link-fn :fi)
     :link-sv (link-fn :sv)
     :created-date created-date}))

(def ^:private oir-reminder-email-conf
  {:recipients-fn  notifications/from-data
   :subject-key    "open-inforequest-reminder"
   :model-fn       oir-reminder-base-email-model
   :application-fn (fn [{id :id}] (mongo/by-id :applications id))})

(notifications/defemail :reminder-open-inforequest oir-reminder-email-conf)

;; For the "Neighbor reminder"

(notifications/defemail :reminder-neighbor (assoc neighbors/email-conf :subject-key "neighbor-reminder"))



;; "Pyyntoon ei ole vastattu viikon kuluessa ja hakemuksen tila on valmisteilla tai vireilla. Lahetetaan viikoittain uudelleen."
(defn statement-request-reminder []
  ;;
  (let [timestamp-1-week-ago (get-timestamp-from-now :week 1)
;        _ (println "\n statement-request-reminder, (older-than timestamp-1-week-ago): " (older-than timestamp-1-week-ago))
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
                                                          :data {:email (get-app-owner-email app)
                                                                 :created-date (util/to-local-date requested)}})
      (update-application (application->command app)
        {:statements {$elemMatch {:id (:id statement)}}}
        {$set {:statements.$.reminder-sent (now)}}))))


;; "Neuvontapyyntoon ei ole vastattu viikon kuluessa eli neuvontapyynnon tila on avoin. Lahetetaan viikoittain uudelleen."
(defn open-inforequest-reminder []
  (let [timestamp-1-week-ago (get-timestamp-from-now :week 1)
        oirs (mongo/select :open-inforequest-token {:created (older-than timestamp-1-week-ago)
                                                    :last-used nil
                                                    $or [{:reminder-sent {$exists false}}
                                                         {:reminder-sent nil}
                                                         {:reminder-sent (older-than timestamp-1-week-ago)}]})]
    (doseq [oir oirs]
      (notifications/notify! :reminder-open-inforequest {:application (mongo/by-id :applications (:application-id oir))
                                                         :data {:email (:email oir)
                                                                :token-id (:id oir)
                                                                :created-date (util/to-local-date (:created oir))}})
      (mongo/update-by-id :open-inforequest-token (:id oir) {$set {:reminder-sent (now)}}))))


;; "Kuulemisen tila on "Sahkoposti lahetetty", eika allekirjoitusta ole tehty viikon kuluessa ja hakemuksen tila on valmisteilla tai vireilla. Muistutus lahetetaan kerran."
(defn neighbor-reminder []
  (let [timestamp-1-week-ago (get-timestamp-from-now :week 1)
        apps (mongo/select :applications {:state {$in ["open" "submitted"]}
                                          :neighbors {$elemMatch {:status.state {$in ["email-sent"]}
                                                                  :status.created (older-than timestamp-1-week-ago)}}})]
    (doseq [app apps
            neighbor (:neighbors app)
            :let [statuses (:status neighbor)]]

      (when (not-any? #(= "reminder-sent" (:state %)) statuses)
        (doseq [status statuses]

          (when (and
                  (= "email-sent" (:state status))
                  (< (:created status) timestamp-1-week-ago))
            (notifications/notify! :reminder-neighbor {:application app
                                                       :data {:email (:email status)
                                                              :token (:token status)
                                                              :neighborId (:id neighbor)}})

            ;; *** TODO: Mista tietaa onnistuiko emailin lahetys? Vain onnistuessa pitaisi paivittaa kantaa. ****
            (update-application (application->command app)
              {:neighbors {$elemMatch {:id (:id neighbor)}}}
              {$push {:neighbors.$.status {:state    "reminder-sent"
                                           :token    (:token status)
                                           :created  (now)}}})))))))


;; "Hakemuksen tila on valmisteilla tai vireilla, mutta edellisesta paivityksesta on aikaa yli kuukausi. Lahetetaan kuukausittain uudelleen."
(defn application-state-reminder []
  (let [timestamp-1-month-ago (get-timestamp-from-now :month 1)
        apps (mongo/select :applications {:state {$in ["open" "submitted"]}
                                          :modified (older-than timestamp-1-month-ago)
                                          $or [{:reminder-sent {$exists false}}
                                               {:reminder-sent nil}
                                               {:reminder-sent (older-than timestamp-1-month-ago)}]})]
    (doseq [app apps]
      (notifications/notify! :reminder-application-state {:application app
                                                          :data {:email (get-app-owner-email app)}})
      (update-application (application->command app)
        {$set {:reminder-sent (now)}}))))


(defn send-reminder-emails [& args]
    (statement-request-reminder)
    (open-inforequest-reminder)
    (neighbor-reminder)
    (application-state-reminder))

