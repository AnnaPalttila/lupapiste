(ns lupapalvelu.application-from-prev-permit-itest
  (:require [midje.sweet :refer :all]
            [clojure.java.io :as io]
            [sade.core :refer [def-]]
            [sade.xml :as xml]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.factlet :refer :all]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.fixture.core :as fixture]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.xml.krysp.application-from-krysp :as krysp-fetch-api]
            [lupapalvelu.xml.krysp.reader :as krysp-reader]
            [sade.http :as http]
            [lupapalvelu.itest-util :as util]))

(fixture/apply-fixture "minimal")

(def- example-kuntalupatunnus "14-0241-R 3")
(def- example-LP-tunnus "LP-186-2014-00290")

(defn- create-app-from-prev-permit [apikey & args]
 (let [args (->> args
              (apply hash-map)
              (merge {:lang "fi"
                      :organizationId "186-R"  ;; Jarvenpaan rakennusvalvonta
                      :kuntalupatunnus example-kuntalupatunnus
                      :y 0
                      :x 0
                      :address ""
                      :propertyId nil})
              (mapcat seq))]
   (apply local-command apikey :create-application-from-previous-permit args)))

(let [example-xml (xml/parse (slurp (io/resource "../resources/krysp/sample/verdict-rakval-from-kuntalupatunnus-query.xml")))
      example-app-info (krysp-reader/get-app-info-from-message example-xml example-kuntalupatunnus)]

  (facts "Creating new application based on a prev permit"

    (fact "missing parameters"
      (create-app-from-prev-permit raktark-jarvenpaa :organizationId "") => (partial expected-failure? "error.missing-parameters"))

    ; 1: hakijalla ei ole oiketta noutaa aiempaa lupaa
    (fact "applicant cannot create application"
      (create-app-from-prev-permit pena
        :x "6707184.319"
        :y "393021.589"
        :address "Kylykuja 3"
        :propertyId "18600303560005") => (partial expected-failure? "error.unauthorized"))

    ; 2: Kannassa on ei-peruutettu hakemus, jonka organization ja verdictin kuntalupatunnus matchaa haettuihin. Palautuu lupapiste-tunnus, jolloin hakemus avataan.
    (fact "db has app that has the kuntalupatunnus in its verdict and its organization matches"
      (create-app-from-prev-permit raktark-jarvenpaa) => (contains {:ok true :id "lupis-id"})
      (provided
        (domain/get-application-as anything anything) => {:id "lupis-id" :state "verdictGiven"}))

    ; 3: jos taustajarjestelmasta ei saada xml-sisaltoa -> (fail :error.no-previous-permit-found-from-backend)
    (fact "no xml content received from backend with the kuntalupatunnus"
      (create-app-from-prev-permit raktark-jarvenpaa) => (partial expected-failure? "error.no-previous-permit-found-from-backend")
      (provided
        (krysp-fetch-api/get-application-xml anything anything) => nil))

    ; 4: jos (krysp-reader/get-app-info-from-message xml kuntalupatunnus) palauttaa nillin -> (fail :error.no-previous-permit-found-from-backend)
    (fact "no application info could be parsed"
      (create-app-from-prev-permit raktark-jarvenpaa) => (partial expected-failure? "error.no-previous-permit-found-from-backend")
      (provided
        (krysp-reader/get-app-info-from-message anything anything) => nil))

    ; 5: jos parametrina annettu organisaatio ja app-infosta ratkaistu organisaatio ei matchaa -> (fail :error.previous-permit-found-from-backend-is-of-different-organization)
    (fact "ids of the given and resolved organizations do not match"
      (create-app-from-prev-permit raktark-jarvenpaa) => (partial expected-failure? "error.previous-permit-found-from-backend-is-of-different-organization")
      (provided
        (krysp-reader/get-app-info-from-message anything anything) => {:municipality "753"}))

    ; 6: jos sanomassa ei ollut rakennuspaikkaa, ja ei alunperin annettu tarpeeksi parametreja -> (fail :error.more-prev-app-info-needed :needMorePrevPermitInfo true)
    (fact "no 'rakennuspaikkatieto' element in the received xml, need more info"
      (create-app-from-prev-permit raktark-jarvenpaa) => (contains {:ok false
                                                                    :needMorePrevPermitInfo true
                                                                    :text "error.more-prev-app-info-needed"})
      (provided
        (krysp-reader/get-app-info-from-message anything anything) => (dissoc example-app-info :rakennuspaikka)))

    ; 7: testaa Sonjalla, etta ei ole oikeuksia luoda hakemusta, mutta jarvenpaan viranomaisella on
    (facts "authority tests"

      (fact "authority of different municipality cannot create application"
        (create-app-from-prev-permit sonja
          :x "6707184.319"
          :y "393021.589"
          :address "Kylykuja 3"
          :propertyId "18600303560005") => (partial expected-failure? "error.unauthorized"))

      (fact* "authority of same municipality can create application"
        (let [resp (create-app-from-prev-permit raktark-jarvenpaa
                     :x "6707184.319"
                     :y "393021.589"
                     :address "Kylykuja 3"
                     :propertyId "18600303560005") => ok?
              app-id (:id resp)
              application (query-application local-query raktark-jarvenpaa app-id)
              invites (filter #(= raktark-jarvenpaa-id (get-in % [:invite :inviter :id])) (:auth application))]

        ;; Test count of the invited emails, because invalid emails are ignored
        (fact "invites count"
          (count invites) => 3
          (count (:invites (local-query pena  :invites))) => 1
          (count (:invites (local-query mikko :invites))) => 1
          (count (:invites (local-query teppo :invites))) => 1)

        ;; Cancel the application and re-call 'create-app-from-prev-permit' -> should open application with different ID
        (fact "fetching prev-permit again after canceling the previously fetched one"
          (local-command raktark-jarvenpaa :cancel-application-authority
            :id (:id application)
            :text "Se on peruutus ny!"
            :lang "fi")
          (let [resp (create-app-from-prev-permit raktark-jarvenpaa
                       :x "6707184.319"
                       :y "393021.589"
                       :address "Kylykuja 3"
                       :propertyId "18600303560005") => ok?]
            (:id resp) =not=> app-id)))))


    ;; This applies to all tests in this namespace
    (against-background
      (krysp-fetch-api/get-application-xml anything anything) => example-xml))

  (facts "Application from kuntalupatunnus via rest API"
    (let [rest-address (str (server-address) "/rest/get-lp-id-from-previous-permit")
          params  {:query-params {"kuntalupatunnus" example-kuntalupatunnus}
                   :basic-auth   ["jarvenpaa-backend" "jarvenpaa"]}]
      (against-background [(before :facts (apply-remote-minimal))]
        (fact "should create new LP application if kuntalupatunnus doesn't match existing app"
          (let [response (http/get rest-address params)
                resp-body (:body (util/decode-response response))]
            (:status response) => 200
            resp-body => ok?
            (keyword (:text resp-body)) => :created-new-application))

        (fact "should return the LP application if the kuntalupatunnus matches an existing app"
          (let [{app-id :id} (create-and-submit-application pena :municipality jarvenpaa-muni)
                verdict-resp (give-verdict raktark-jarvenpaa app-id :verdictId example-kuntalupatunnus)
                response     (http/get rest-address params)
                resp-body    (:body (util/decode-response response))]
            verdict-resp => ok?
            (:status response) => 200
            resp-body => ok?
            (keyword (:text resp-body)) => :already-existing-application))

        (fact "create new LP app if kuntalupatunnus matches existing app in another organization"
         (let [{app-id :id} (create-and-submit-application pena :municipality sonja-muni)
               _            (give-verdict sonja app-id :verdictId example-kuntalupatunnus)
               response     (http/get rest-address params)
               resp-body    (:body (util/decode-response response))]
           (:status response) => 200
           resp-body => ok?
           (keyword (:text resp-body)) => :created-new-application))))))
