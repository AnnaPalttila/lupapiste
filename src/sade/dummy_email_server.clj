(ns sade.dummy-email-server
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error fatal]]
            [clojure.java.io :as io]
            [clojure.pprint]
            [noir.core :refer [defpage]]
            [net.cgrand.enlive-html :as enlive]
            [sade.email :as email]
            [sade.env :as env]
            [sade.core :refer [ok fail now]]
            [sade.util :as util]
            [lupapalvelu.action :refer [defquery defcommand]]
            [sade.crypt :as crypt])
  (:import [org.apache.commons.io IOUtils]))

;;
;; Dummy email server:
;;

(when (env/value :email :dummy-server)

  (info "Initializing dummy email server")

  (defonce sent-messages (atom []))

  (defn attachment-as-base64-str [file]
    (with-open [i (io/input-stream file)]
      (-> i
        (IOUtils/toByteArray)
        (crypt/base64-encode)
        (crypt/bytes->str))))

  (defn parse-body [body {content-type :type content :content}]
    (if (and content-type content)
      (let [attachment (when (= :attachment content-type)
                         (attachment-as-base64-str content))
            content (or attachment content)]
        (assoc body (condp = content-type
                      "text/plain; charset=utf-8" :plain
                      "text/html; charset=utf-8"  :html
                      content-type) content))
      body))

  (defn deliver-email [to subject body]
    (assert (string? to) "must provide 'to'")
    (assert (string? subject) "must provide 'subject'")
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

  (defquery "sent-emails"
    {:user-roles #{:anonymous}}
    [{{reset :reset :or {reset false}} :data}]
    (ok :messages (messages :reset reset)))

  (defquery "last-email"
    {:user-roles #{:anonymous}}
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
                                                                                     {:tag :dd :attrs {:id "time"} :content [(util/to-local-datetime (:time msg))]}]}
                                                                 {:tag :hr}]))))
      {:status 404 :body "No emails"}))

  (info "Dummy email server initialized"))

