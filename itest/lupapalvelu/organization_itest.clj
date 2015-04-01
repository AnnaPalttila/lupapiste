(ns lupapalvelu.organization-itest
  (:require [midje.sweet :refer :all]
            [lupapalvelu.organization :as local-org-api]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.factlet :refer [fact* facts*]]
            [lupapalvelu.itest-util :refer :all]))

(apply-remote-minimal)

(facts* "users-in-same-organizations"
  (let [naantali (apikey-for "rakennustarkastaja@naantali.fi")
        jarvenpaa (apikey-for "rakennustarkastaja@jarvenpaa.fi")
        oulu (apikey-for "olli")

        naantali-user (query naantali :user) => ok?
        jarvenpaa-user (query jarvenpaa :user) => ok?
        oulu-user (query oulu :user) => ok?]

    ; Meta
    (fact "naantali user in naantali & jarvenpaa orgs"
      (->> naantali-user :user :orgAuthz (map :org)) => ["529-R" "186-R"])
    (fact "jarvenpaa just jarvenpaa"
      (->> jarvenpaa-user :user :orgAuthz (map :org)) => ["186-R"])
    (fact "oulu user in oulu & naantali orgs"
      (->> oulu-user :user :orgAuthz (map :org)) => ["564-R" "529-R" "564-R"])


    (let [naantali-sees (:users (query naantali :users-in-same-organizations))
          jarvenpaa-sees (:users (query jarvenpaa :users-in-same-organizations))
          oulu-sees (:users (query oulu :users-in-same-organizations))]

      (fact "naantali user sees other users in naantali & jarvenpaa (but not admin)"
        (map :username naantali-sees) =>
        (contains ["rakennustarkastaja@naantali.fi" "lupasihteeri@naantali.fi" "rakennustarkastaja@jarvenpaa.fi" "lupasihteeri@jarvenpaa.fi" "olli"] :in-any-order))

      (fact "jarvenpaa just jarvenpaa users (incl. Mr. Naantali but not admin)"
        (map :username jarvenpaa-sees) =>
        (contains ["rakennustarkastaja@jarvenpaa.fi" "lupasihteeri@jarvenpaa.fi" "rakennustarkastaja@naantali.fi"] :in-any-order))

      (fact "oulu user sees other users in oulu & naantali"
        (map :username oulu-sees) =>
        (contains ["olli" "rakennustarkastaja@naantali.fi" "lupasihteeri@naantali.fi"] :in-any-order)))))

(fact* "Organization details query works"
 (let [resp  (query pena "organization-details" :municipality "753" :operation "kerrostalo-rivitalo" :lang "fi") => ok?]
   (count (:attachmentsForOp resp )) => pos?
   (count (:links resp)) => pos?))

(fact* "The query /organizations"
  (let [resp (query admin :organizations) => ok?]
    (count (:organizations resp)) => pos?))

(fact "Update organization"
  (let [organization         (first (:organizations (query admin :organizations)))
        orig-scope           (first (:scope organization))
        organization-id      (:id organization)
        resp                 (command admin :update-organization
                               :permitType (:permitType orig-scope)
                               :municipality (:municipality orig-scope)
                               :inforequestEnabled (not (:inforequest-enabled orig-scope))
                               :applicationEnabled (not (:new-application-enabled orig-scope))
                               :openInforequestEnabled (not (:open-inforequest orig-scope))
                               :openInforequestEmail "someone@localhost"
                               :opening nil)
        updated-organization (query admin :organization-by-id :organizationId organization-id)
        updated-scope        (local-org-api/resolve-organization-scope (:municipality orig-scope) (:permitType orig-scope) updated-organization)]

    resp => ok?

    (fact "inforequest-enabled" (:inforequest-enabled updated-scope) => (not (:inforequest-enabled orig-scope)))
    (fact "new-application-enabled" (:new-application-enabled updated-scope) => (not (:new-application-enabled orig-scope)))
    (fact "open-inforequest" (:open-inforequest updated-scope) => (not (:open-inforequest orig-scope)))
    (fact "open-inforequest-email" (:open-inforequest-email updated-scope) => "someone@localhost")))

(fact* "Tampere-ya sees (only) YA operations and attachments (LUPA-917, LUPA-1006)"
  (let [resp (query tampere-ya :organization-by-user) => ok?
        tre  (:organization resp)]
    (keys (:operationsAttachments tre)) => [:YA]
    (-> tre :operationsAttachments :YA) => truthy
    (keys (:attachmentTypes resp)) => [:YA]
    (-> resp :attachmentTypes :YA) => truthy))


(facts "Selected operations"

  (fact* "For an organization which has no selected operations, all operations are returned"
    (let [resp (query sipoo "all-operations-for-organization" :organizationId "753-YA") => ok?
          operations (:operations resp)]
      ;; All the YA operations (and only those) are received here.
      (count operations) => 1
      (-> operations first first) => "yleisten-alueiden-luvat"))

  (fact* "Set selected operations"
    (command pena "set-organization-selected-operations" :operations ["pientalo" "aita"]) => unauthorized?
    (command sipoo "set-organization-selected-operations" :operations ["pientalo" "aita"]) => ok?)

  (fact* "Query selected operations"
    (query pena "selected-operations-for-municipality" :municipality "753") => ok?
    (let [resp (query sipoo "selected-operations-for-municipality" :municipality "753")]
      resp => ok?

      ;; Received the two selected R operations plus 4 YA operations.
      (:operations resp) =>  [["Rakentaminen ja purkaminen"
                               [["Uuden rakennuksen rakentaminen"
                                 [["pientalo" "pientalo"]]]
                                ["Rakennelman rakentaminen"
                                 [["Aita" "aita"]]]]]
                              ["yleisten-alueiden-luvat"
                               [["sijoituslupa"
                                 [["pysyvien-maanalaisten-rakenteiden-sijoittaminen"
                                   [["vesi-ja-viemarijohtojen-sijoittaminen" "ya-sijoituslupa-vesi-ja-viemarijohtojen-sijoittaminen"]]]]]
                                ["katulupa" [["kaivaminen-yleisilla-alueilla"
                                              [["vesi-ja-viemarityot" "ya-katulupa-vesi-ja-viemarityot"]]]]]
                                ["kayttolupa" [["mainokset" "ya-kayttolupa-mainostus-ja-viitoitus"]
                                               ["terassit" "ya-kayttolupa-terassit"]]]]]]))

  (fact* "Query selected operations"
    (let [id   (create-app-id pena :operation "kerrostalo-rivitalo" :municipality sonja-muni)
          resp (query pena "addable-operations" :id id) => ok?]
      (:operations resp) => [["Rakentaminen ja purkaminen" [["Uuden rakennuksen rakentaminen" [["pientalo" "pientalo"]]] ["Rakennelman rakentaminen" [["Aita" "aita"]]]]]]))

  (fact* "The query 'organization-by-user' correctly returns the selected operations of the organization"
    (let [resp (query pena "organization-by-user") => unauthorized?
          resp (query sipoo "organization-by-user") => ok?]
      (get-in resp [:organization :selectedOperations]) => {:R ["aita" "pientalo"]}))

  (fact "An application query correctly returns the 'required fields filling obligatory' and 'kopiolaitos-email' info in the organization meta data"
    (let [app-id (create-app-id pena :operation "kerrostalo-rivitalo" :municipality sonja-muni)
          app    (query-application pena app-id)
          org    (query admin "organization-by-id" :organizationId  (:organization app))
          kopiolaitos-email "kopiolaitos@example.com"
          kopiolaitos-orderer-address "Testikatu 1"
          kopiolaitos-orderer-phone "123"
          kopiolaitos-orderer-email "orderer@example.com"]

;      (fact "the 'app-required-fields-filling-obligatory' and 'kopiolaitos-email' flags have not yet been set for organization in db"
;        (:app-required-fields-filling-obligatory org) => nil
;        (-> app :organizationMeta :requiredFieldsFillingObligatory) => false)

;      (command sipoo "set-organization-app-required-fields-filling-obligatory" :isObligatory false) => ok?

      (let [app    (query-application pena app-id)
            org    (query admin "organization-by-id" :organizationId  (:organization app))
            organizationMeta (:organizationMeta app)]
        (fact "the 'app-required-fields-filling-obligatory' is set to False"
          (:app-required-fields-filling-obligatory org) => false
          (:requiredFieldsFillingObligatory organizationMeta) => false)
        (fact "the 'kopiolaitos-email' is set (from minimal)"
          (:kopiolaitos-email org) => "sipoo@example.com"
          (get-in organizationMeta [:kopiolaitos :kopiolaitosEmail]) => "sipoo@example.com")
        (fact "the 'kopiolaitos-orderer-address' is set (from minimal)"
          (:kopiolaitos-orderer-address org) => "Testikatu 2, 12345 Sipoo"
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererAddress]) => "Testikatu 2, 12345 Sipoo")
        (fact "the 'kopiolaitos-orderer-email' is set (from minimal)"
          (:kopiolaitos-orderer-email org) => "tilaaja@example.com"
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererEmail]) => "tilaaja@example.com")
        (fact "the 'kopiolaitos-orderer-phone' is set (from minimal)"
          (:kopiolaitos-orderer-phone org) => "0501231234"
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererPhone]) => "0501231234"))

      (command sipoo "set-organization-app-required-fields-filling-obligatory" :isObligatory true) => ok?
      (command sipoo "set-kopiolaitos-info"
        :kopiolaitosEmail kopiolaitos-email
        :kopiolaitosOrdererAddress kopiolaitos-orderer-address
        :kopiolaitosOrdererPhone kopiolaitos-orderer-phone
        :kopiolaitosOrdererEmail kopiolaitos-orderer-email) => ok?

      (let [app    (query-application pena app-id)
            org    (query admin "organization-by-id" :organizationId  (:organization app))
            organizationMeta (:organizationMeta app)]
        (fact "the 'app-required-fields-filling-obligatory' flag is set to true value"
          (:app-required-fields-filling-obligatory org) => true
          (:requiredFieldsFillingObligatory organizationMeta) => true)
        (fact "the 'kopiolaitos-email' flag is set to given email address"
          (:kopiolaitos-email org) => kopiolaitos-email
          (get-in organizationMeta [:kopiolaitos :kopiolaitosEmail]) => kopiolaitos-email)
        (fact "the 'kopiolaitos-orderer-address' flag is set to given address"
          (:kopiolaitos-orderer-address org) => kopiolaitos-orderer-address
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererAddress]) => kopiolaitos-orderer-address)
        (fact "the 'kopiolaitos-orderer-phone' flag is set to given phone address"
          (:kopiolaitos-orderer-phone org) => kopiolaitos-orderer-phone
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererPhone]) => kopiolaitos-orderer-phone)
        (fact "the 'kopiolaitos-orderer-email' flag is set to given email address"
          (:kopiolaitos-orderer-email org) => kopiolaitos-orderer-email
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererEmail]) => kopiolaitos-orderer-email)))))

(facts "municipality-active"
  (fact "only info requests enabled"
    (let [m (query pena :municipality-active :municipality "997")]
      (:applications m) => empty?
      (:infoRequests m) => ["R"]
      (:opening m) => empty?))
  (fact "only applications enabled"
    (let [m (query pena :municipality-active :municipality "998")]
      (:applications m) => ["R"]
      (:infoRequests m) => empty?
      (:opening m) => empty?))
  (fact "nothing enabled, but coming"
    (command admin :update-organization
      :permitType "R"
      :municipality "999"
      :inforequestEnabled false
      :applicationEnabled false
      :openInforequestEnabled false
      :openInforequestEmail "someone@localhost"
      :opening 123)
    (let [m (query pena :municipality-active :municipality "999")]
      (:applications m) => empty?
      (:infoRequests m) => empty?
      (:opening m) => [{:permitType "R", :opening 123}])))


(facts "organization-operations-attachments"
  (fact "Invalid operation is rejected"
    (command sipoo :organization-operations-attachments :operation "foo" :attachments []) => (partial expected-failure? "error.unknown-operation"))

  (fact "Empty attachments array is ok"
    (command sipoo :organization-operations-attachments :operation "pientalo" :attachments []) => ok?)

  (fact "scalar value as attachments parameter is not ok"
    (command sipoo :organization-operations-attachments :operation "pientalo" :attachments "") => (partial expected-failure? "error.non-vector-parameters"))

  (fact "Invalid attachment is rejected"
    (command sipoo :organization-operations-attachments :operation "pientalo" :attachments [["foo" "muu"]]) => (partial expected-failure? "error.unknown-attachment-type"))

  (fact "Valid attachment is ok"
    (command sipoo :organization-operations-attachments :operation "pientalo" :attachments [["muut" "muu"]]) => ok?))
