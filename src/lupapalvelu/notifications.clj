(ns lupapalvelu.notifications
  (:use [monger.operators]
        [clojure.tools.logging]
        [sade.strings :only [suffix]]
        [lupapalvelu.core]
        [lupapalvelu.i18n :only [loc]])
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [sade.strings :as ss]
            [net.cgrand.enlive-html :as enlive]
            [sade.security :as sadesecurity]
            [sade.client :as sadeclient]
            [sade.email :as email]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.security :as security]
            [lupapalvelu.client :as client]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.components.core :as c]
            [noir.request :as request]))

;;
;; Helpers
;;

(defn emit [xml] (apply str (enlive/emit* xml)))

(defmacro message [& xml] `(emit (-> ~@xml)))

(def mail-agent (agent nil))

(defn get-styles []
  (slurp (io/resource "email-templates/styles.css")))

(defn get-application-link [{:keys [infoRequest id]} lang suffix host]
  (let [permit-type-path (if infoRequest "/inforequest" "/application")
        full-path        (str permit-type-path "/" id suffix)]
    (str host "/app/" lang "/applicant?hashbang=!" full-path "#!" full-path)))

(defn replace-style [e style]
  (enlive/transform e [:style] (enlive/content style)))

(defn replace-application-link [e selector application lang suffix host]
  (enlive/transform e [(keyword (str selector lang))]
    (fn [e] (assoc-in e [:attrs :href] (get-application-link application lang suffix host)))))

(defn send-mail-to-recipients! [recipients title msg]
  (doseq [recipient recipients]
    (send-off mail-agent (fn [_]
                           (if (email/send-mail recipient title msg)
                             (info "email was sent successfully")
                             (error "email could not be delivered."))))))

(defn get-email-title [{:keys [title]} title-key]
  (i18n/with-lang "fi"
    (let [title-postfix (i18n/loc (s/join "." ["email" "title" title-key]))]
      (str "Lupapiste: " title " - " title-postfix))))

(defn- url-to [to]
  (let [request (request/ring-request)
        scheme (get request :scheme)
        host (get-in request [:headers "host"])]
    (str (name scheme) "://" host (if-not (ss/starts-with to "/") "/") to)))

(defn get-email-recipients-for-application [application]
  (map (fn [user] (:email (mongo/by-id :users (:id user)))) (:auth application)))

(defn template [s]
  (->
    (str "email-templates/" s)
    enlive/html-resource
    (replace-style (get-styles))))

;;
;; Sending
;;

; new comment
(defn get-message-for-new-comment [application host]
  (message
    (template "application-new-comment.html")
    (replace-application-link "#conversation-link-" application "fi" "/conversation" host)
    (replace-application-link "#conversation-link-" application "sv" "/conversation" host)))

(defn send-notifications-on-new-comment! [application user-commenting comment-text host]
  (when (security/authority? user-commenting)
    (let [recipients (get-email-recipients-for-application application)
          msg        (get-message-for-new-comment application host)
          title      (get-email-title application "new-comment")]
      (send-mail-to-recipients! recipients title msg))))

;; invite
(defn send-invite! [email text application user host]
  (let [title (get-email-title application "invite")
        msg   (message
                (template "invite.html")
                (enlive/transform [:.name] (enlive/content (str (:firstName user) " " (:lastName user))))
                (replace-application-link "#link-" application "fi" "" host)
                (replace-application-link "#link-" application "sv" "" host)
                )]
    (send-mail-to-recipients! [email] title msg)))

;; create-statement-person
(comment
  (defn send-on-create-statement-person! [email text application host]
    (let [title (get-email-title application "create-statement-person")
          msg   (message
                  (template "add-statement-person.html")
                  (enlive/transform [:.name] (enlive/content (str (:firstName user) " " (:lastName user))))
                  (replace-application-link "#link-" application "fi" "" host)
                  (replace-application-link "#link-" application "sv" "" host))]
      (send-mail-to-recipients! [email] title msg))))

; application opened
(defn get-message-for-application-state-change [application host]
  (message
    (template "application-state-change.html")
    (replace-application-link "#application-link-" application "fi" "" host)
    (replace-application-link "#application-link-" application "sv" "" host)
    (enlive/transform [:#state-fi] (enlive/content (i18n/with-lang "fi" (i18n/loc (str (:state application))))))
    (enlive/transform [:#state-sv] (enlive/content (i18n/with-lang "sv" (i18n/loc (str (:state application))))))))

(defn send-notifications-on-application-state-change! [application-id host]
  (let [application (mongo/by-id :applications application-id)
        recipients  (get-email-recipients-for-application application)
        msg         (get-message-for-application-state-change application host)
        title       (get-email-title application "state-change")]
    (send-mail-to-recipients! recipients title msg)))

; verdict given
(defn get-message-for-verdict [application host]
  (message
    (template "application-verdict.html")
    (replace-application-link "#verdict-link-" application "fi" "/verdict" host)
    (replace-application-link "#verdict-link-" application "sv" "/verdict" host)))

(defn send-notifications-on-verdict! [application-id host]
  (let [application (mongo/by-id :applications application-id)
        recipients  (get-email-recipients-for-application application)
        msg         (get-message-for-verdict application host)
        title       (get-email-title application "verdict")]
    (send-mail-to-recipients! recipients title msg)))

(defn send-password-reset-email! [to token]
  (let [link-fi (url-to (str "/app/fi/welcome#!/setpw/" token))
        link-sv (url-to (str "/app/sv/welcome#!/setpw/" token))
        msg (message
              (template "password-reset.html")
              (enlive/transform [:#link-fi] (fn [a] (assoc-in a [:attrs :href] link-fi)))
              (enlive/transform [:#link-sv] (fn [a] (assoc-in a [:attrs :href] link-sv))))]
    (send-mail-to-recipients! [to] (loc "reset.email.title") msg)))
