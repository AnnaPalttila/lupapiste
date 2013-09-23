(ns lupapalvelu.notifications-test
  (:require [lupapalvelu.notifications :refer :all]
            [midje.sweet :refer :all]
            [lupapalvelu.mongo :as mongo]
            [sade.dummy-email-server :as dummy]))

(facts "email titles"
  (get-email-title {:title "Haavikontie 9, Tampere"} "new-comment") => "Lupapiste.fi: Haavikontie 9, Tampere - uusi kommentti"
  (get-email-title {:title "Haavikontie 9, Tampere"}) => "Lupapiste.fi: Haavikontie 9, Tampere")

(fact "create application link"
  (fact "..for application"
    (get-application-link {:id 1} "" "http://localhost:8080" "fi")
      => "http://localhost:8080/app/fi/applicant?hashbang=!/application/1#!/application/1")
  (fact "..for inforequest"
    (get-application-link {:id 1 :infoRequest true} "/comment" "http://localhost:8080" "fi")
      => "http://localhost:8080/app/fi/applicant?hashbang=!/inforequest/1/comment#!/inforequest/1/comment"))

(fact "Email for new comment contains link to application"
  (get-message-for-new-comment { :id 123 :permitType "application"} "http://localhost:8000") => (contains "http://localhost:8000/app/fi/applicant?hashbang=!/application/123/conversation#!/application/123/conversation"))

(fact "Every user gets an email"
  (get-email-recipients-for-application { :auth [{:id "a" :role "owner"} 
                                                 {:id "b" :role "writer"} 
                                                 {:id "c" :role "unknown"}] :title "title" } 
                                        nil nil) => [ "a@foo.com" "b@foo.com" "c@foo.com"]
  (provided 
    (mongo/by-id :users "a" {:email 1}) => {:email "a@foo.com"}
    (mongo/by-id :users "b" {:email 1}) => {:email "b@foo.com"}
    (mongo/by-id :users "c" {:email 1}) => {:email "c@foo.com"}))

(fact "Every user except with role unknown get email"
  (get-email-recipients-for-application { :auth [{:id "a" :role "owner"} 
                                                 {:id "b" :role "writer"} 
                                                 {:id "c" :role "unknown"}] :title "title" } 
                                        nil ["unknown"]) => [ "a@foo.com" "b@foo.com"]
  (provided 
    (mongo/by-id :users "a" {:email 1}) => {:email "a@foo.com"}
    (mongo/by-id :users "b" {:email 1}) => {:email "b@foo.com"}))

(fact "Only writers get email"
  (get-email-recipients-for-application { :auth [{:id "a" :role "owner"} 
                                                 {:id "w1" :role "writer"}
                                                 {:id "w2" :role "writer"}
                                                 {:id "w3" :role "writer"}
                                                 {:id "c" :role "unknown"}] :title "title" }
                                        ["writer"] nil) => [ "w1@foo.com" "w2@foo.com" "w3@foo.com"]
  (provided 
    (mongo/by-id :users "w1" {:email 1}) => {:email "w1@foo.com"}
    (mongo/by-id :users "w2" {:email 1}) => {:email "w2@foo.com"}
    (mongo/by-id :users "w3" {:email 1}) => {:email "w3@foo.com"}))

(fact "Only writers get email (when excluded)"
  (get-email-recipients-for-application { :auth [{:id "a" :role "owner"} 
                                                 {:id "w1" :role "writer"}
                                                 {:id "w2" :role "writer"}
                                                 {:id "w3" :role "writer"}
                                                 {:id "c" :role "unknown"}] :title "title" }
                                        ["owner" "writer"] ["owner"]) => [ "w1@foo.com" "w2@foo.com" "w3@foo.com"]
  (provided 
    (mongo/by-id :users "w1" {:email 1}) => {:email "w1@foo.com"}
    (mongo/by-id :users "w2" {:email 1}) => {:email "w2@foo.com"}
    (mongo/by-id :users "w3" {:email 1}) => {:email "w3@foo.com"}))

(fact "Email for application open is like"
  (let [msg (get-message-for-application-state-change { :id 123 :state "open"} "http://localhost:8000")]
    msg => (contains "http://localhost:8000/app/sv/applicant?hashbang=!/application/123#!/application/123")
    msg => (contains "Valmisteilla")))

(fact "Email for application submitted contains the state string."
  (get-message-for-application-state-change { :state "submitted"} ..host..) => (contains "Vireill\u00E4"))

(fact send-open-inforequest-invite!
  (dummy/reset-sent-messages)
  (send-open-inforequest-invite! "foo@example.com" "123" "abc" "http://lupapiste.fi") => nil
  (let [{:keys [html plain]} (-> dummy/sent-messages deref first :body)]
    html => #"^\<html\>"
    html => #"Uusi neuvontapyynt\u00F6:"
    html => #"\<a.*href=\"http://lupapiste.fi/api/raw/openinforequest\?token-id=123\""
    plain => #"Uusi neuvontapyynt\u00F6:"
    plain => #"http://lupapiste.fi/api/raw/openinforequest\?token-id=123"))

