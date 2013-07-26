(ns sade.dummy-email-server
  (:require [sade.email]
            [sade.env :as env]
            [clojure.pprint]
            [noir.core :refer [defpage]]
            [net.cgrand.enlive-html :as enlive]
            [clojure.java.io :as io]
            [lupapalvelu.core :refer [defquery defcommand ok fail now]]))

;;
;; Dummy email server:
;;

(def sent-messages (atom []))

(defn parse-body [body {content-type :type content :content}]
  (if (and content-type content)
    (assoc body (condp = content-type
                  "text/plain; charset=utf-8" :plain
                  "text/html; charset=utf-8"  :html
                  content-type) content)
    body))

(defn deliver-email [to subject body]
  (assert to "must provide 'to'")
  (assert subject "must provide 'subject'")
  (assert body "must provide 'body'")
  (swap! sent-messages conj {:to to
                             :subject subject
                             :body (reduce parse-body {} body)
                             :time (now)})
  nil)

(alter-var-root (var sade.email/deliver-email) (constantly deliver-email))

(defn reset-sent-messages []
  (reset! sent-messages []))

(defn messages [& {reset :reset :or {reset false}}]
  (let [m @sent-messages]
    (when reset (reset-sent-messages))
    m))

(defn dump-sent-messages []
  (doseq [message (messages)]
    (clojure.pprint/pprint message)))

(defcommand "send-email"
  {:parameters [:to :subject :template]}
  [{{:keys [to subject template] :as data} :data}]
  (if-let [error (sade.email/send-email-message to subject template (dissoc data :from :to :subject :template))]
    (fail "send-email-message failed" error)
    (ok)))

(defquery "sent-emails"
  {}
  [{{reset :reset :or {reset false}} :data}]
  (ok :messages (messages :reset reset)))

(defquery "last-email"
  {}
  [{{reset :reset :or {reset true}} :data}]
  (ok :message (last (messages :reset reset))))

(defpage "/api/last-email" {reset :reset}
  (if-let [msg (last (messages :reset reset))]
    (enlive/emit* (-> (enlive/html-resource (io/input-stream (.getBytes (get-in msg [:body :html]) "UTF-8")))
                    (enlive/transform [:head] (enlive/append {:tag :title :content (:subject msg)}))
                    (enlive/transform [:body] (enlive/prepend [{:tag :dl :content [{:tag :dt :content "To"}
                                                                                   {:tag :dd :attrs {:id "to"} :content [(:to msg)]}
                                                                                   {:tag :dt :content "Subject"}
                                                                                   {:tag :dd :attrs {:id "subject"} :content [(:subject msg)]}
                                                                                   {:tag :dt :content "Time"}
                                                                                   {:tag :dd :attrs {:id "time"} :content [(:time msg)]}]}
                                                               {:tag :hr}]))))
    {:status 404 :body "No emails"}))
