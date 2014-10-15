(ns lupapalvelu.notifications
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error fatal]]
            [clojure.set :as set]
            [clojure.string :as s]
            [sade.util :refer [future* to-local-date fn->]]
            [sade.env :as env]
            [sade.strings :as ss]
            [sade.email :as email]
            [lupapalvelu.i18n :refer [loc] :as i18n]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.user :as u]))

;;
;; Helpers
;;

(defn get-application-link [{:keys [infoRequest id]} suffix lang {role :role :or {role "applicant"}}]
  (let [permit-type-path (if infoRequest "/inforequest" "/application")
        full-path        (str permit-type-path "/" id suffix)]
    (str (env/value :host) "/app/" lang "/" (u/applicationpage-for role) "#!" full-path)))

(defn- ->to [{:keys [email firstName lastName]}]
  (letfn [(sanit [s] (s/replace s #"[<>]"  ""))]
    (if (or (ss/blank? firstName) (ss/blank? lastName))
      email
      (str (sanit firstName) " " (sanit lastName) " <" (sanit email) ">"))))

(defn- send-mail-to-recipient! [recipient subject msg]
  {:pre [(map? recipient) (:email recipient)]}
  (let [to (->to recipient)]
    (if (env/value :email :dummy)
     (email/send-email-message to subject msg)
     (future*
       (if (email/send-email-message to subject msg)
         (error "email could not be delivered." to subject msg)
         (info "email was sent successfully." to subject)))))
  nil)

(defn- get-email-subject [{title :title
                           municipality :municipality} & [title-key show-municipality-in-subject]]
  (let [title-postfix (when title-key (if (i18n/has-term? "fi" "email.title" title-key)
                                        (i18n/localize "fi" "email.title" title-key)
                                        (i18n/localize "fi" title-key)))
        title-begin (str (when show-municipality-in-subject
                         (str (i18n/localize "fi" "municipality" municipality) ", ")) title)]
    (str "Lupapiste.fi: " title-begin (when (and title title-key)" - ") (when title-key title-postfix))))

(defn- get-email-recipients-for-application
  "Emails are sent to everyone in auth array except statement persons,
   those who haven't accepted invite or have unsubscribed emails."
  [{:keys [auth statements]} included-roles excluded-roles]
  {:post [every? map? %]}
  (let [users            (->> auth (remove :invite) (remove :unsubscribed))
        included-users   (if (seq included-roles)
                           (filter (fn [user] (some #(= (:role user) %) included-roles)) users)
                           users)
        auth-recipients (->> included-users
                           (filter (fn [user] (not-any? #(= (:role user) %) excluded-roles)))
                           (map #(u/non-private (u/get-user-by-id (:id %)))))
        statement-giver-emails (set (map #(-> % :person :email) statements))]
    (if (some #(= "statementGiver" %) excluded-roles)
      (remove #(statement-giver-emails (:email %)) auth-recipients)
      auth-recipients)))

;;
;; Model creation functions
;;

;; Application (the default)
(defn create-app-model [{application :application} {tab :tab} recipient]
  {:link-fi (get-application-link application tab "fi" recipient)
   :link-sv (get-application-link application tab "sv" recipient)
   :state-fi (i18n/localize :fi (str (:state application)))
   :state-sv (i18n/localize :sv (str (:state application)))
   :modified (to-local-date (:modified application))})


;;
;; Recipient functions
;;

(defn- default-recipients-fn [{application :application}]
  (get-email-recipients-for-application application nil ["statementGiver"]))
(defn from-user [command] [(:user command)])
(defn from-data [{data :data}] (let [email (:email data)
                                     emails (if (sequential? email) email [email])]
                                 (map (fn [addr] {:email addr}) emails)))

;;
;; Configuration for generic notifications
;;

(defonce ^:private mail-config (atom {}))

;;
;; Public API
;;

(defn defemail [template-name m]
  (swap! mail-config assoc template-name m))

(defn notify! [template-name command]
  {:pre [(template-name @mail-config)]}
  (let [conf (template-name @mail-config)]
    (when ((get conf :pred-fn (constantly true)) command)
      (let [application-fn (get conf :application-fn identity)
            application    (application-fn (:application command))
            command        (assoc command :application application)
            recipients-fn  (get conf :recipients-fn default-recipients-fn)
            recipients     (remove (fn-> :email ss/blank?) (recipients-fn command))
            subject        (get-email-subject application (get conf :subject-key (name template-name)) (get conf :show-municipality-in-subject false))
            model-fn       (get conf :model-fn create-app-model)
            template-file  (get conf :template (str (name template-name) ".md"))]
        (doseq [recipient recipients]
          (let [model (model-fn command conf recipient)
                msg   (email/apply-template template-file model)]
            (send-mail-to-recipient! recipient subject msg)))))))
