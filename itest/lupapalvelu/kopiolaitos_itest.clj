(ns lupapalvelu.kopiolaitos-itest
  (:require [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.factlet :refer :all]
            [lupapalvelu.kopiolaitos :refer :all]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.i18n :as i18n]
            [sade.util :as util]
            [sade.crypt :as crypt])
  (:import  [java.util.zip ZipInputStream]))

(testable-privates lupapalvelu.kopiolaitos
  get-kopiolaitos-html-table
  get-kopiolaitos-email-addresses)


(fact "Setting invalid kopiolaitos email fails"
  (command sipoo :set-kopiolaitos-info
    :kopiolaitosEmail "sipoo.nodomain"
    :kopiolaitosOrdererAddress "Testikatu 2, 12345 Sipoo"
    :kopiolaitosOrdererPhone "0501231234"
    :kopiolaitosOrdererEmail "tilaaja@example.com") => (partial expected-failure? "error.set-kopiolaitos-info.invalid-email"))

(fact "Sonja sets default values for organization kopiolaitos info"
  (command sipoo :set-kopiolaitos-info
    :kopiolaitosEmail "sipoo@example.com;sipoo2@example.com"
    :kopiolaitosOrdererAddress "Testikatu 2, 12345 Sipoo"
    :kopiolaitosOrdererPhone "0501231234"
    :kopiolaitosOrdererEmail "tilaaja@example.com") => ok?

  (query sipoo :kopiolaitos-config) => {:ok true
                                        :kopiolaitos-email "sipoo@example.com;sipoo2@example.com"
                                        :kopiolaitos-orderer-address "Testikatu 2, 12345 Sipoo"
                                        :kopiolaitos-orderer-email "tilaaja@example.com"
                                        :kopiolaitos-orderer-phone "0501231234"})

(facts "Kopiolaitos order"
  (let [app (create-and-submit-application pena)
        app-id (:id app)
        _ (upload-attachment-to-all-placeholders pena app)
        app (query-application pena app-id)]

    (fact* "Sonja sets two attachments to be verdict attachments"
      (command sonja :set-attachments-as-verdict-attachment
        :id app-id
        :selectedAttachmentIds (map :id (take 2 (:attachments app)))
        :unSelectedAttachmentIds []) => ok?

      (let [app (query-application sonja app-id) => map?
            attachments (:attachments app) => sequential?]
        (fact "Two attachments have forPrinting flags set to true"
          (count (filter :forPrinting attachments)) => 2)))

    (command sonja :check-for-verdict :id app-id) => ok?
    (last-email)  ;; Inbox reset

    (fact "Order prints"
      (let [app (query-application sonja app-id)
            attachments-with-amount (map
                                      #(assoc %
                                         :amount   "2"
                                         :filename (-> % :latestVersion :filename)
                                         :fileId   (-> % :latestVersion :fileId)
                                         :contents (or (:contents %) (i18n/localize "fi" (str "attachmentType." (-> % :type :type-group) "." (-> % :type :type-id)))))
                                      (:attachments app))
            order-info {:ordererOrganization "Testi"
                        :ordererAddress      "Testikuja 2"
                        :ordererPhone        "12345"
                        :ordererEmail        "test@example.com"
                        :applicantName       "Hakija"
                        :kuntalupatunnus     "2013-1"
                        :propertyId          "32131654"
                        :lupapisteId         (:id app)
                        :address             (:title app)
                        :contentsTable       (get-kopiolaitos-html-table "fi" attachments-with-amount)}]

        (fact "with invalid orderInfo order fails"
          (command sonja :order-verdict-attachment-prints
            :id app-id
            :lang "fi"
            :attachmentsWithAmounts nil
            :orderInfo (dissoc order-info :applicantName)) => fail?)
        (fact "without attachments order fails"
          (command sonja :order-verdict-attachment-prints
            :id app-id
            :lang "fi"
            :attachmentsWithAmounts nil
            :orderInfo order-info) => fail?)
        (fact
          "without attachment versions order fails"
          (command sonja :order-verdict-attachment-prints
            :id app-id
            :lang "fi"
            :attachmentsWithAmounts (map #(dissoc % :versions) (filter :forPrinting attachments-with-amount))
            :orderInfo order-info) => fail?)
        (fact "attachments without needed attachment key (amount) results in fail"
          (command sonja :order-verdict-attachment-prints
            :id app-id
            :lang "fi"
            :attachmentsWithAmounts (:attachments app)
            :orderInfo order-info) => (and fail? (contains {:required-keys ["forPrinting" "amount" "versions"]})))
        (fact "fail when all attachments for command don't have forPriting set to 'true'"
          (command sonja :order-verdict-attachment-prints
            :id app-id
            :lang "fi"
            :attachmentsWithAmounts attachments-with-amount
            :orderInfo order-info) => fail?)
        (fact "success as organization has email set"
          (command sonja :order-verdict-attachment-prints
            :id app-id
            :lang "fi"
            :attachmentsWithAmounts (filter :forPrinting attachments-with-amount)
            :orderInfo order-info) => ok?)

        (facts* "sent emails were correct"
          (let [sent-emails (sent-emails)]  ;; resets sent emails list
            (count sent-emails) => 2
            (map :to sent-emails) => (just #{"sipoo@example.com" "sipoo2@example.com"})

            (fact "check attachment contents of both sent emails"
              (doseq [email sent-emails]
                (let [zip-stream (-> email
                                   (get-in [:body :attachment])
                                   (crypt/str->bytes)
                                   (crypt/base64-decode)
                                   (clojure.java.io/input-stream)
                                   (ZipInputStream.))
                      to-zip-entries (fn [s result]
                                       (if-let [entry (.getNextEntry s)]
                                         (recur s (conj result (bean entry)))
                                         result))
                      result (to-zip-entries zip-stream [])]

                  (fact "zip file has two files"
                    (count result) => 2)
                  (fact "filenames end with 'test-attachment.txt'"
                    (every? #(.endsWith (:name %) "test-attachment.txt") result) => true))))))

        (fact "unsetting organization kopiolaitos-email leads to failure in kopiolaitos order"
          (command sipoo :set-kopiolaitos-info
            :kopiolaitosEmail ""
            :kopiolaitosOrdererAddress ""
            :kopiolaitosOrdererPhone ""
            :kopiolaitosOrdererEmail "") => ok?
          (command sonja :order-verdict-attachment-prints
            :id app-id
            :lang "fi"
            :attachmentsWithAmounts (filter :forPrinting attachments-with-amount)
            :orderInfo order-info) => (partial expected-failure? "no-kopiolaitos-email-defined"))))))


;; Testing the support for multiple email addresses

(facts "multiple emails concatenated as one kopiolaitos email address"

  (facts "separating emails from the kopiolaitos email addresses"
    (fact "same email multiple times plus different separator chars"
      (util/separate-emails "pena.bulkki@example.com,pena.bulkki@example.com") => #{"pena.bulkki@example.com"})

    (fact "different separator chars"
      (util/separate-emails "pena.bulkki@example.com, pena.bulkkinen@example.com;mikko.alakesko@example.com")
        => #{"pena.bulkki@example.com" "pena.bulkkinen@example.com" "mikko.alakesko@example.com"})

    (fact "one email"
      (util/separate-emails "pena.bulkki@example.com") => #{"pena.bulkki@example.com"}))

  (fact "one kopiolaitos email is invalid"
    (get-kopiolaitos-email-addresses sonja-muni) => (throws Exception #"kopiolaitos-invalid-email")
    (provided
      (organization/with-organization sonja-muni :kopiolaitos-email) => "pena.bulkki@example.com;mikko.nodomain")))

