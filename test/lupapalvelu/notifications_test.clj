(ns lupapalvelu.notifications-test
  (:require [lupapalvelu.mongo :as mongo])
  (:use lupapalvelu.notifications
        midje.sweet))

(facts "email titles"
  (get-email-title {:title "Haavikontie 9, Tampere"} "new-comment") => "Lupapiste: Haavikontie 9, Tampere - uusi kommentti"
  (get-email-title {:title "Haavikontie 9, Tampere"}) => "Lupapiste: Haavikontie 9, Tampere")

(fact "create application link"
  (fact "..for application"
    (get-application-link {:id 1} "" "http://localhost:8080" "fi")
      => "http://localhost:8080/app/fi/applicant?hashbang=!/application/1#!/application/1")
  (fact "..for inforequest"
    (get-application-link {:id 1 :infoRequest true} "/comment" "http://localhost:8080" "fi")
      => "http://localhost:8080/app/fi/applicant?hashbang=!/inforequest/1/comment#!/inforequest/1/comment"))

(fact "invite"
  (with-redefs [send-mail-to-recipients! (fn [email title msg] msg)]
    (send-invite! ..email.. ..text.. {:id 1} ..host..) => (contains "<p>Hei,</p>")))
    #_  (provided
          (send-mail-to-recipients! ..email.. anything msg) => msg)

(fact "Email for new comment contains link to application"
  (get-message-for-new-comment { :id 123 :permitType "application"} "http://localhost:8000") => (contains "http://localhost:8000/app/fi/applicant?hashbang=!/application/123/conversation#!/application/123/conversation"))

(fact "All application users get emails"
  (get-email-recipients-for-application { :auth [{:id "a" :role "owner"} {:id "b" :role "writer"} {:id "c" :role "unknown"}] :title "title" }) => [ "a@foo.com" "b@foo.com" "c@foo.com"]
  (provided (mongo/by-id :users "a") => {:email "a@foo.com"}
    (mongo/by-id :users "b") => {:email "b@foo.com"}
    (mongo/by-id :users "c") => {:email "c@foo.com"}))

(fact "Email for application open is like"
  (let [msg (get-message-for-application-state-change { :id 123 :state "open"} "http://localhost:8000")]
    msg => (contains "http://localhost:8000/app/sv/applicant?hashbang=!/application/123#!/application/123")
    msg => (contains "Valmisteilla")))

(fact "Email for application submitted contains the state string."
  (get-message-for-application-state-change { :state "submitted"} ..host..) => (contains "Vireill\u00E4"))
