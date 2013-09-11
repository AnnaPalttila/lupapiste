(ns lupapalvelu.application-itest
  (:use [lupapalvelu.itest-util]
        [midje.sweet]
        [lupapalvelu.factlet]
        [clojure.pprint :only [pprint]]
        [clojure.string :only [join]])
  (:require [lupapalvelu.operations :as operations]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.document.schemas :as schemas]))

(apply-remote-minimal)

#_(fact "can't inject js in 'x' or 'y' params"
   (create-app pena :x ";alert(\"foo\");" :y "what ever") =not=> ok?
   (create-app pena :x "0.1x" :y "1.0")                   =not=> ok?
   (create-app pena :x "1x2" :y "1.0")                    =not=> ok?
   (create-app pena :x "2" :y "1.0")                      =not=> ok?
   (create-app pena :x "410000.1" :y "6610000.1")         => ok?)

(fact "creating application without message"
  (let [id    (create-app-id pena)
        resp  (query pena :application :id id)
        app   (:application resp)]
    app => (contains {:id id
                      :state "draft"
                      :location {:x 444444.0 :y 6666666.0}
                      :organization "753-R"})
    (count (:comments app)) => 0
    (first (:auth app)) => (contains
                             {:firstName "Pena"
                              :lastName "Panaani"
                              :type "owner"
                              :role "owner"})
    (:allowedAttachmentTypes app) => (complement empty?)))

(fact "creating application with message"
  (let [application-id  (create-app-id pena :messages ["hello"])
        resp            (query pena :application :id application-id)
        application     (:application resp)
        hakija (domain/get-document-by-name application "hakija")]
    (:state application) => "draft"
    (:opened application) => nil
    (count (:comments application)) => 1
    (-> (:comments application) first :text) => "hello"
    (lupapalvelu.document.tools/unwrapped (-> hakija :data :henkilo :henkilotiedot)) => (contains {:etunimi "Pena" :sukunimi "Panaani"})))

(fact "application created to Sipoo belongs to organization Sipoon Rakennusvalvonta"
  (let [application-id  (create-app-id pena :municipality "753")
        resp            (query pena :application :id application-id)
        application     (:application resp)
        hakija (domain/get-document-by-name application "hakija")]
    (:organization application) => "753-R"))

(fact "application created to Tampere belongs to organization Tampereen Rakennusvalvonta"
  (let [application-id  (create-app-id pena :municipality "837")
        resp            (query pena :application :id application-id)
        application     (:application resp)
        hakija (domain/get-document-by-name application "hakija")]
    (:organization application) => "837-R"))

(fact "application created to Reisjarvi belongs to organization Peruspalvelukuntayhtyma Selanne"
  (let [application-id  (create-app-id pena :municipality "626")
        resp            (query pena :application :id application-id)
        application     (:application resp)
        hakija (domain/get-document-by-name application "hakija")]
    (:organization application) => "069-R"))

(fact "Application in Sipoo has two possible authorities: Sonja and Ronja."
  (let [id (create-app-id pena :municipality sonja-muni)]
    (comment-application id pena)
    (let [query-resp   (query sonja :authorities-in-applications-organization :id id)]
      (success query-resp) => true
      (count (:authorityInfo query-resp)) => 2)))

(fact "Assign application to an authority"
  (let [application-id (create-app-id pena :municipality sonja-muni)
        ;; add a comment to change state to open
        _ (comment-application application-id pena)
        application (:application (query sonja :application :id application-id))
        authority-before-assignation (:authority application)
        authorities (:authorityInfo (query sonja :authorities-in-applications-organization :id application-id))
        authority (first authorities)
        resp (command sonja :assign-application :id application-id :assigneeId (:id authority))
        assigned-app (:application (query sonja :application :id application-id))
        authority-after-assignation (:authority assigned-app)]
    application-id => truthy
    application => truthy
    (success resp) => true
    authority-before-assignation => nil
    authority-after-assignation => (contains {:id (:id authority)})
    (fact "Authority is not able to submit"
      sonja =not=> (allowed? sonja :submit-application :id application-id))))

(fact "Assign application to an authority and then to no-one"
  (let [application-id (create-app-id pena :municipality sonja-muni)
        ;; add a comment change set state to open
        _ (comment-application application-id pena)
        application (:application (query sonja :application :id application-id))
        authority-before-assignation (:authority application)
        authorities (:authorityInfo (query sonja :authorities-in-applications-organization :id application-id))
        authority (first authorities)
        resp (command sonja :assign-application :id application-id :assigneeId (:id authority))
        resp (command sonja :assign-application :id application-id :assigneeId nil)
        assigned-app (:application (query sonja :application :id application-id))
        authority-in-the-end (:authority assigned-app)]
    authority-before-assignation => nil
    authority-in-the-end => nil))

(fact "Applicaton shape is saved"
  (let [shape "POLYGON((460620 7009542,362620 6891542,467620 6887542,527620 6965542,460620 7009542))"
        application-id (create-app-id pena)
        resp (command pena :save-application-shape :id application-id :shape shape)
        resp (query pena :application :id application-id)
        app   (:application resp)]
    (first (:shapes app)) => shape))

(fact "Authority is able to create an application to a municipality in own organization"
  (let [application-id  (create-app-id sonja :municipality sonja-muni)]
    (fact "Application is open"
       (let [query-resp      (query sonja :application :id application-id)
             application     (:application query-resp)]
         query-resp  => ok?
         application => truthy
         (:state application) => "open"
         (:opened application) => truthy
         (:opened application) => (:created application)))
    (fact "Authority could submit her own application"
      sonja => (allowed? :submit-application :id application-id))
    (fact "Application is submitted"
      (let [resp        (command sonja :submit-application :id application-id)
            application (:application (query sonja :application :id application-id))]
        resp => ok?
        (:state application) => "submitted"))))

(facts* "Application has opened when submitted from draft"
  (let [resp (create-app pena) => ok?
        id   (:id resp)
        app1 (query pena :application :id id) => ok?
        resp (command pena :submit-application :id id) => ok?
        resp (query pena :application :id id) => ok?
        app2 (:application resp)]
    (:opened app1) => nil
    (:opened app2) => number?))

(fact "Authority is able to add an attachment to an application after verdict has been given for it"
  (doseq [user [sonja pena]]
    (let [application-id  (create-app-id user :municipality sonja-muni)
          resp            (command user :submit-application :id application-id)
          application     (:application (query user :application :id application-id))]
      (success resp) => true
      (:state application) => "submitted"

      (let [resp        (command sonja :give-verdict :id application-id :verdictId "aaa" :status 42 :name "Paatoksen antaja" :given 123 :official sonja-id)
            application (:application (query sonja :application :id application-id))]
        (success resp) => true
        (:state application) => "verdictGiven"

        (let [attachment-id (first (get-attachment-ids application))]
          (upload-attachment sonja (:id application) attachment-id true "R")
          (upload-attachment pena (:id application) attachment-id false "R"))))))

(fact "Authority in unable to create an application to a municipality in another organization"
  (unauthorized (create-app sonja :municipality veikko-muni)) => true)

(facts "Add operations"
  (let [application-id  (create-app-id mikko :municipality veikko-muni)]
    (comment-application application-id mikko)
    (command veikko :assign-application :id application-id :assigneeId veikko-id) => ok?

    (fact "Applicant is able to add operation"
      (success (command mikko :add-operation :id application-id :operation "varasto-tms")) => true)

    (fact "Authority is able to add operation"
      (success (command veikko :add-operation :id application-id :operation "muu-uusi-rakentaminen")) => true)))

(fact "adding comments"
  (let [{id :id}  (create-and-submit-application pena)]
    (fact "applicant can't comment with to"
      pena =not=> (allowed? :can-target-comment-to-authority)
      pena =not=> (allowed? :add-comment :id id :to irrelevant)
      (command pena :add-comment :id id :text "comment1" :target "application") => ok?
      (command pena :add-comment :id id :text "comment1" :target "application" :to sonja-id) =not=> ok?)
    (fact "authority can comment with to"
      sonja => (allowed? :can-target-comment-to-authority)
      sonja => (allowed? :add-comment :id id :to sonja-id)
      (command sonja :add-comment :id id :text "comment1" :target "application") => ok?
      (command sonja :add-comment :id id :text "comment1" :target "application" :to sonja-id) => ok?)))

(fact "create-and-submit-application"
  (let [app  (create-and-submit-application pena)]
    (:state app) => "submitted"))

(fact "Pena cannot create app for organization that has new applications disabled"
  (let [resp  (create-app pena :municipality "997")]
    resp =not=> ok?
    (:text resp) => "error.new-applications-disabled"))

(defn- set-and-check-person [api-key application-id initial-document path]
  (fact "initially there is no person data"
       initial-document => truthy
       (get-in initial-document [:data :henkilotiedot]) => nil)

    (fact "new person is set"
      (command api-key :set-user-to-document :id application-id :documentId (:id initial-document) :userId mikko-id :path (if (seq path) (join "." path) "")) => ok?
      (let [updated-app (:application (query mikko :application :id application-id))
            update-doc (domain/get-document-by-id updated-app (:id initial-document))
            schema-name  (get-in update-doc [:schema :info :name])
            schema       (schemas/get-schema schema-name)
            person-path  (into [] (concat [:data] (map keyword path) [:henkilotiedot]))]

        (get-in update-doc (into person-path [:etunimi :value])) => "Mikko"
        (get-in update-doc (into person-path [:sukunimi :value])) => "Intonen")))

(facts "Set user to document"
  (let [application-id   (create-app-id mikko :municipality sonja-muni)
        application      (:application (query mikko :application :id application-id))
        paasuunnittelija (domain/get-document-by-name application "paasuunnittelija")
        suunnittelija    (domain/get-document-by-name application "suunnittelija")
        hakija     (domain/get-document-by-name application "hakija")
        maksaja    (domain/get-document-by-name application "maksaja")]

    (set-and-check-person mikko application-id paasuunnittelija [])
    (set-and-check-person mikko application-id hakija ["henkilo"])
    (set-and-check-person mikko application-id maksaja ["henkilo"])

    (fact "there is no suunnittelija"
       suunnittelija => truthy
       (get-in suunnittelija [:data :henkilotiedot]) => nil)

    (let [doc-id (:id suunnittelija)
          code "RAK-rakennesuunnittelija"]

      (fact "suunnittelija kuntaroolikoodi is set"
        (command mikko :update-doc :id application-id :doc doc-id :updates [["kuntaRoolikoodi" code]]) => ok?
        (let [updated-app          (:application (query mikko :application :id application-id))
              updated-suunnittelija (domain/get-document-by-id updated-app doc-id)]
          updated-suunnittelija => truthy
          (get-in updated-suunnittelija [:data :kuntaRoolikoodi :value]) => code))

      (fact "new suunnittelija is set"
        (command mikko :set-user-to-document :id application-id :documentId (:id suunnittelija) :userId mikko-id :path "") => ok?
        (let [updated-app           (:application (query mikko :application :id application-id))
              updated-suunnittelija (domain/get-document-by-id updated-app doc-id)]
          (get-in updated-suunnittelija [:data :henkilotiedot :etunimi :value]) => "Mikko"
          (get-in updated-suunnittelija [:data :henkilotiedot :sukunimi :value]) => "Intonen"
          (fact "suunnittelija kuntaroolikoodi is preserved (LUPA-774)"
            (get-in updated-suunnittelija [:data :kuntaRoolikoodi :value]) => code))))))

(fact "Merging building information from KRYSP does not overwrite the rest of the document"
  (let [application-id  (create-app-id pena :municipality "753")
        resp            (command pena :add-operation :id application-id :operation "kayttotark-muutos")
        app             (:application (query pena :application :id application-id))
        rakmuu-doc      (domain/get-document-by-name app "rakennuksen-muuttaminen")
        resp2           (command pena :update-doc :id application-id :doc (:id rakmuu-doc) :updates [["muutostyolaji" "muut muutosty\u00f6t"]])
        updated-app     (:application (query pena :application :id application-id))
        building-info   (command pena :get-building-info-from-legacy :id application-id)
        doc-before      (domain/get-document-by-name updated-app "rakennuksen-muuttaminen")
        building-id     (:buildingId (first (:data building-info)))
        resp3           (command pena :merge-details-from-krysp :id application-id :documentId (:id doc-before) :buildingId building-id)
        merged-app      (:application (query pena :application :id application-id))
        doc-after       (domain/get-document-by-name merged-app "rakennuksen-muuttaminen")]
        (get-in doc-before [:data :muutostyolaji :value]) => "muut muutosty\u00f6t"        
        (get-in doc-after [:data :muutostyolaji :value]) => "muut muutosty\u00f6t"
        (get-in doc-after [:data :kaytto :kayttotarkoitus :source]) => "krysp"))

(comment
  (apply-remote-minimal)
  ; Do 70 applications in each municipality:
  (doseq [muni ["753" "837" "186"]
          address-type ["Katu " "Kuja " "V\u00E4yl\u00E4 " "Tie " "Polku " "H\u00E4meentie " "H\u00E4meenkatu "]
          address (map (partial str address-type) (range 1 11))]
    (create-app pena :municipality muni :address address)))

