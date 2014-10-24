(ns lupapalvelu.reminders-itest
  (:require [clojure.java.io :as io]
            [monger.operators :refer :all]
            [midje.sweet :refer :all]
            [lupapalvelu.core :refer [now]]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.factlet :refer [fact* facts*]]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.action :refer :all]
            [sade.dummy-email-server :as dummy-email-server]
            [lupapalvelu.fixture :as fixture]
            [lupapalvelu.batchrun :as batchrun]))

(def ^:private timestamp-the-beginning-of-time 0)
(def ^:private timestamp-1-day-ago (batchrun/get-timestamp-from-now :day 1))

(def ^:private neighbor-non-matching
  {:id "534bf825299508fb3618489v"
   :propertyId "p"
   :owner {:type nil
           :name "n"
           :email "e"
           :businessID nil
           :nameOfDeceased nil
           :address {:street "s", :city "c", :zip "z"}}
   :status [{:state "open"
             :created 1}
            {:state "email-sent"
             :created timestamp-1-day-ago
             :email "abba@example.com"
             :token "Ww4yJgCmPyuqkWdQNiODsp1gHBsTCYHhfGaGaRDc5kMEP5Ar"
             :user {:enabled true,
                    :lastName "Panaani"
                    :firstName "Pena"
                    :city "Piippola"
                    :username "pena"
                    :street "Paapankuja 12"
                    :phone "0102030405"
                    :email "pena@example.com"
                    :personId "010203-040A"
                    :role "applicant"
                    :zip "10203"
                    :id "777777777777777777000020"}}]})

(def ^:private neighbor-matching
  (-> neighbor-non-matching
    (assoc :id "534bf825299508fb3618456c")
    (assoc-in [:status 1 :created] timestamp-the-beginning-of-time)))

(def ^:private neighbor-non-matching-with-response-given
  (-> neighbor-matching
    (assoc :id "534bf825299508fb3615223m")
    (update-in [:status] conj {:state "response-given-ok"
                               :message ""
                               :user nil
                               :created timestamp-1-day-ago
                               :vetuma {:stamp "70505470151426009182"
                                        :userid "210281-9988"
                                        :city nil
                                        :zip nil
                                        :street nil
                                        :lastName "TESTAA"
                                        :firstName "PORTAALIA"}})))

(def ^:private statement-non-matching
  {:id "525533f7e4b0138a23d8r4b4"
    :given nil
    :person {:id "5252ecdfe4b0138a23d8e385"
             :text "Palotarkastus"
             :email "pekka.lupapiste@gmail.com"
             :name "Pekka Lupapiste"}
    :requested timestamp-1-day-ago
    :status nil})

(def ^:private statement-matching
  {:id "525533f7e4b0138a23d8e4b5"
   :given nil
   :person {:id "5252ecdfe4b0138a23d8e385"
            :text "Turvatarkastus"
            :email "esa.lupapiste@gmail.com"
            :name "Esa Lupapiste"}
   :requested timestamp-the-beginning-of-time
   :status nil})

(def ^:private app-id
  "LP-753-2014-12345")

(def ^:private reminder-application
  {:sent nil
   :neighbors [neighbor-non-matching
               neighbor-matching]
   :schema-version 1
   :authority {}
   :auth [{:lastName "Panaani"
           :firstName "Pena"
           :username "pena"
           :type "owner"
           :role "owner"
           :id "777777777777777777000020"}]
   :drawings []
   :submitted nil
   :state "open"
   :permitSubtype nil
   :tasks []
   :closedBy {}
   :_verdicts-seen-by {}
   :location {:x 444444.0, :y 6666666.0}
   :attachments []
   :statements [statement-non-matching
                statement-matching]
   :organization "753-R"
   :buildings []
   :title "Naapurikuja 3"
   :started nil
   :closed nil
   :operations [{:id "534bf825299508fb3618455d"
                 :name "asuinrakennus"
                 :created 1397487653097}]
   :infoRequest false
   :openInfoRequest false
   :opened 1397487653750
   :created 1397487653097
   :_comments-seen-by {}
   :propertyId "75312312341234"
   :verdicts []
   :startedBy {}
   :documents []
   :_statements-seen-by {}
   :modified timestamp-the-beginning-of-time
   :comments []
   :address "Naapurikuja 3"
   :permitType "R"
   :id app-id
   :municipality "753"})

(def ^:private reminder-application-non-matching-neighbors
  (assoc reminder-application
    :id "LP-753-2014-123456789"
    :modified timestamp-1-day-ago
    :statements [statement-non-matching]
    :neighbors [neighbor-non-matching-with-response-given]))

(def ^:private reminder-application-matching-to-inforequest
  (assoc reminder-application
    :id "LP-732-2013-00006"
    :state "info"
    :infoRequest true
    :openInfoRequest true
    :modified timestamp-1-day-ago
    :statements []
    :neighbors []))

(def ^:private reminder-application-non-matching-to-inforequest
  (assoc reminder-application
    :id "LP-732-2013-00007"
    :state "canceled"))

(def ^:private open-inforequest-entry-non-matching {:_id "0yqaV2vEcGDH9LYaLFOlxSTpLidKI7xWbuJ9IGGv0iPM0Rrd"
                                                    :application-id (:id reminder-application-matching-to-inforequest)
                                                    :created timestamp-1-day-ago
                                                    :email "reba.skebamies@example.com"
                                                    :last-used nil
                                                    :organization-id "732-R"})

(def ^:private open-inforequest-entry-matching
  (-> open-inforequest-entry-non-matching
    (assoc
      :_id "0yqaV2vEcGDH9LYaLFOlxSTpLidKI7xWbuJ9IGGv0iPM0Rv2"
      :created timestamp-the-beginning-of-time
      :email "juba.skebamies@example.com")))

(def ^:private open-inforequest-entry-with-application-with-non-matching-state
  (-> open-inforequest-entry-non-matching
    (assoc
      :_id "0yqaV2vEcGDH9LYaLFOlxSTpLidKI7xWbuJ9IGGv0iPM0As5"
      :email "jyba.skebamies@example.com"
      :application-id (:id reminder-application-non-matching-to-inforequest))))



(defn- check-sent-reminder-email [to subject bodypart]
  (let [emails (dummy-email-server/messages :reset true)]
    (fact "email count"
      (count emails) => 1)

    (let [email (last emails)]
      (fact "email check"
        (:to email) => (contains to)
        (:subject email) => subject
        (get-in email [:body :plain]) => (contains bodypart)))))


(facts "reminders"

 (mongo/clear!)
 (fixture/apply-fixture "minimal")
 (mongo/insert :applications reminder-application)
 (mongo/insert :applications reminder-application-non-matching-neighbors)
 (mongo/insert :applications reminder-application-matching-to-inforequest)
 (mongo/insert :applications reminder-application-non-matching-to-inforequest)
 (mongo/insert :open-inforequest-token open-inforequest-entry-non-matching)
 (mongo/insert :open-inforequest-token open-inforequest-entry-matching)
 (mongo/insert :open-inforequest-token open-inforequest-entry-with-application-with-non-matching-state)
 (dummy-email-server/messages :reset true)  ;; clears inbox


 (facts "statement-request-reminder"

   (fact "the \"reminder-sent\" timestamp does not pre-exist"
     (let [now-timestamp (now)]

       (batchrun/statement-request-reminder)

       (let [app (mongo/by-id :applications app-id)]
         (> (-> app :statements second :reminder-sent) now-timestamp) => true?
         (-> app :statements first :reminder-sent) => nil?
         )

       (check-sent-reminder-email
         (-> statement-matching :person :email)
         "Lupapiste.fi: Naapurikuja 3 - Muistutus lausuntopyynn\u00f6st\u00e4"
         "Sinulta on pyydetty lausuntoa lupahakemukseen")
       ))

   (fact "the \"reminder-sent\" timestamp already exists"
     (update-application
       (application->command reminder-application)
       {:statements {$elemMatch {:id (:id statement-matching)}}}
       {$set {:statements.$.reminder-sent timestamp-the-beginning-of-time}})

     (batchrun/statement-request-reminder)

     (let [app (mongo/by-id :applications app-id)]
       (> (-> app :statements second :reminder-sent) timestamp-the-beginning-of-time) => true?)

     (check-sent-reminder-email
       (-> statement-matching :person :email)
       "Lupapiste.fi: Naapurikuja 3 - Muistutus lausuntopyynn\u00f6st\u00e4"
       "Sinulta on pyydetty lausuntoa lupahakemukseen")
     ))


 (facts "open-inforequest-reminder"

   (fact "the \"reminder-sent\" timestamp does not pre-exist"
     (let [now-timestamp (now)]

       (batchrun/open-inforequest-reminder)

       (let [oir-matching (mongo/by-id :open-inforequest-token (:_id open-inforequest-entry-matching))
             oir-non-matching (mongo/by-id :open-inforequest-token (:_id open-inforequest-entry-non-matching))]
         (> (:reminder-sent oir-matching) now-timestamp) => true?
         (:reminder-sent oir-non-matching) => nil?
         )

       (check-sent-reminder-email
         (:email open-inforequest-entry-matching)
         "Lupapiste.fi: Naapurikuja 3 - Muistutus avoimesta neuvontapyynn\u00f6st\u00e4"
         "Organisaatiollasi on vastaamaton neuvontapyynt\u00f6")
       ))

   (fact "the \"reminder-sent\" timestamp already exists"
     (mongo/update-by-id :open-inforequest-token (:_id open-inforequest-entry-matching)
       {$set {:reminder-sent timestamp-the-beginning-of-time}})

     (batchrun/open-inforequest-reminder)

     (let [oir (mongo/by-id :open-inforequest-token (:_id open-inforequest-entry-matching))]

       (> (:reminder-sent oir) timestamp-the-beginning-of-time) => true?

       (check-sent-reminder-email
         (:email open-inforequest-entry-matching)
         "Lupapiste.fi: Naapurikuja 3 - Muistutus avoimesta neuvontapyynn\u00f6st\u00e4"
         "Organisaatiollasi on vastaamaton neuvontapyynt\u00f6")
       )))


 (facts "neighbor-reminder"

   (fact "the \"reminder-sent\" status does not pre-exist"
     (let [now-timestamp (now)]

       (batchrun/neighbor-reminder)

       (let [app (mongo/by-id :applications app-id)
             reminder-sent-statuses (filter
                                      #(= "reminder-sent" (:state %))
                                      (-> app :neighbors second :status))]

         (count reminder-sent-statuses) => 1
         (> (:created (first reminder-sent-statuses)) now-timestamp) => true?
         (filter
           #(= "reminder-sent" (:state %))
           (-> app :neighbors first :status)) => empty?

         (check-sent-reminder-email
           (-> neighbor-matching :status second :email)
           "Lupapiste.fi: Naapurikuja 3 - Muistutus naapurin kuulemisesta"
           "T\u00e4m\u00e4 on muistutusviesti. Rakennuspaikan rajanaapurina Teille ilmoitetaan")
         )))

   (fact "the \"reminder-sent\" status already exists - no emails sent"
     (dummy-email-server/messages :reset true)  ;; clears inbox
     (batchrun/neighbor-reminder)
     (dummy-email-server/messages :reset true) => empty?))


 (facts "application-state-reminder"

   (fact "the \"reminder-sent\" timestamp does not pre-exist"
     (let [now-timestamp (now)]

       (batchrun/application-state-reminder)

       (let [app (mongo/by-id :applications app-id)]
         (> (:reminder-sent app) now-timestamp) => true?

         (check-sent-reminder-email
           "pena@example.com"
           "Lupapiste.fi: Naapurikuja 3 - Muistutus aktiivisesta hakemuksesta"
           "Sinulla on Lupapiste.fi-palvelussa aktiivinen lupahakemus")
         )))

   (fact "the \"reminder-sent\" timestamp already exists"
     (update-application (application->command reminder-application)
       {$set {:reminder-sent timestamp-the-beginning-of-time}})

     (batchrun/application-state-reminder)

     (let [app (mongo/by-id :applications app-id)]
       (> (:reminder-sent app) timestamp-the-beginning-of-time) => true?

       (check-sent-reminder-email
         "pena@example.com"
         "Lupapiste.fi: Naapurikuja 3 - Muistutus aktiivisesta hakemuksesta"
         "Sinulla on Lupapiste.fi-palvelussa aktiivinen lupahakemus")
       )))

 )

