(ns lupapalvelu.change-permit-itest
  (:require [midje.sweet :refer :all]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.factlet :refer :all]))

(fact* "A change permit can be created based on current R application after verdict has been given."
  (let [apikey                 sonja
        application-id         (create-app-id apikey
                                 :propertyId sipoo-property-id
                                 :address "Paatoskuja 12")
        application            (query-application apikey application-id) => truthy]
    (generate-documents application apikey)
    (command apikey :submit-application :id application-id) => ok?
    (command apikey :approve-application :id application-id :lang "fi") => ok?
    (command apikey :create-change-permit :id application-id) => (partial expected-failure? "error.command-illegal-state")
    (give-verdict apikey application-id) => ok?
    (let [application (query-application apikey application-id)]
      (:state application) => "verdictGiven"
      )
    apikey => (allowed? :create-change-permit :id application-id)))

(fact* "Change permit can only be applied for an R type of application."
  (let [apikey                 sonja
        property-id            sipoo-property-id
        application            (create-and-submit-application apikey
                                 :propertyId property-id
                                 :address "Paatoskuja 13"
                                 :operation "ya-katulupa-vesi-ja-viemarityot") => truthy
        application-id         (:id application)]
    (generate-documents application apikey)
    (command apikey :approve-application :id application-id :lang "fi") => ok?
    (give-verdict apikey application-id) => ok?
    (let [application (query-application apikey application-id) => truthy]
      (:state application) => "verdictGiven")
    (command apikey :create-change-permit :id application-id) => (partial expected-failure? "error.invalid-permit-type")))
