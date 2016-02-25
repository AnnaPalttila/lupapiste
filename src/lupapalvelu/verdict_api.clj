(ns lupapalvelu.verdict-api
  (:require [clojure.walk :as walk]
            [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn warnf error fatal]]
            [pandect.core :as pandect]
            [monger.operators :refer :all]
            [sade.common-reader :as cr]
            [sade.http :as http]
            [sade.strings :as ss]
            [sade.util :as util]
            [sade.core :refer [ok fail fail! ok?]]
            [lupapalvelu.application :as application]
            [lupapalvelu.application-meta-fields :as meta-fields]
            [lupapalvelu.document.transformations :as doc-transformations]
            [lupapalvelu.action :refer [defquery defcommand update-application notify boolean-parameters] :as action]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.verdict :as verdict]
            [lupapalvelu.tiedonohjaus :as t]
            [lupapalvelu.user :as user]
            [lupapalvelu.states :as states]
            [lupapalvelu.state-machine :as sm]
            [lupapalvelu.xml.krysp.application-from-krysp :as krysp-fetch]))

;;
;; KRYSP verdicts
;;

(defn application-has-verdict-given-state [_ application]
  (when-not (and application (some (partial sm/valid-state? application) states/verdict-given-states))
    (fail :error.command-illegal-state)))

(defn normalize-special-verdict
  "Normalizes special foreman/designer verdicts by
  creating a traditional paatostieto element from the proper special
  verdict party.
    application: Application that requests verdict.
    app-xml:     Verdict xml message
  Returns either normalized app-xml (without namespaces) or app-xml if
  the verdict is not special."
  [application app-xml]
  (let [xml (cr/strip-xml-namespaces app-xml)]
    (if (verdict/special-foreman-designer-verdict? (meta-fields/enrich-with-link-permit-data application) xml)
      (verdict/verdict-xml-with-foreman-designer-verdicts application xml)
      app-xml)))

(defn save-verdicts-from-xml
  "Saves verdict's from valid app-xml to application. Returns (ok) with updated verdicts and tasks"
  [{:keys [application] :as command} app-xml]
  (let [updates (verdict/find-verdicts-from-xml command app-xml)]
    (when updates
      (let [doc-updates (doc-transformations/get-state-transition-updates command (sm/verdict-given-state application))]
        (update-application command (:mongo-query doc-updates) (util/deep-merge (:mongo-updates doc-updates) updates))
        (t/mark-app-and-attachments-final! (:id application) (:created command))))
    (ok :verdicts (get-in updates [$set :verdicts]) :tasks (get-in updates [$set :tasks]))))

(defn do-check-for-verdict [{:keys [application] :as command}]
  {:pre [(every? command [:application :user :created])]}
  (when-let [app-xml (krysp-fetch/get-application-xml application :application-id)]
    (let [app-xml (normalize-special-verdict application app-xml)
          organization (organization/get-organization (:organization application))
          validator-fn (permit/get-verdict-validator (permit/permit-type application))
          validation-errors (validator-fn app-xml organization)]
      (or
        validation-errors
        (save-verdicts-from-xml command app-xml)))))

(notifications/defemail :application-verdict
  {:subject-key    "verdict"
   :tab            "verdict"})

(def give-verdict-states (clojure.set/union #{:submitted :complementNeeded :sent} states/verdict-given-states))

(defcommand check-for-verdict
  {:description "Fetches verdicts from municipality backend system.
                 If the command is run more than once, existing verdicts are
                 replaced by the new ones."
   :parameters [:id]
   :states     (conj give-verdict-states :constructionStarted) ; states reviewed 2015-10-12
   :user-roles #{:authority}
   :notified   true
   :pre-checks [application-has-verdict-given-state]
   :on-success (notify :application-verdict)}
  [command]
  (let [result (do-check-for-verdict command)]
    (cond
      (nil? result) (fail :info.no-verdicts-found-from-backend)
      (ok? result) (ok :verdictCount (count (:verdicts result)) :taskCount (count (:tasks result)))
      :else result)))

;;
;; Manual verdicts
;;

(defn- validate-status [{{:keys [status]} :data}]
  (when (and status (or (< status 1) (> status 42)))
    (fail :error.false.status.out.of.range.when.giving.verdict)))

(defcommand new-verdict-draft
  {:parameters [:id]
   :states     give-verdict-states
   :pre-checks [application-has-verdict-given-state]
   :user-roles #{:authority}}
  [{:keys [application] :as command}]
  (let [organization (get application :organization)
        tosFunction (get application :tosFunction)
        metadata (when (seq tosFunction) (t/metadata-for-document organization tosFunction "p\u00e4\u00e4t\u00f6s"))
        blank-verdict (cond-> (domain/->paatos {:draft true})
                              (seq metadata) (assoc :metadata metadata))]
    (update-application command {$push {:verdicts blank-verdict}})
    (ok :verdictId (:id blank-verdict))))

(defn- find-verdict [{verdicts :verdicts} id]
  (some #(when (= id (:id %)) %) verdicts))

(defcommand save-verdict-draft
  {:parameters [:id verdictId :backendId #_:status :name :section :agreement :text :given :official]
   :description  "backendId = Kuntalupatunnus, status = poytakirjat[] / paatoskoodi,
                  name = poytakirjat[] / paatoksentekija, section = poytakirjat[] / pykala
                  agreement = (sopimus), text =  poytakirjat[] / paatos
                  given, official = paivamaarat / antoPvm, lainvoimainenPvm"
   :input-validators [validate-status
                      (partial action/non-blank-parameters [:verdictId])
                      (partial action/boolean-parameters [:agreement])]
   :states     give-verdict-states
   :user-roles #{:authority}
   :pre-checks [application-has-verdict-given-state
                (fn [{{:keys [verdictId]} :data} application]
                  (when verdictId
                    (when-not (:draft (find-verdict application verdictId))
                      (fail :error.verdict.not-draft))))]}
  [{:keys [application created data] :as command}]
  (let [verdict (domain/->paatos
                  (merge
                    (select-keys data [:verdictId :backendId :status :name :section :agreement :text :given :official])
                    {:timestamp created, :draft true}))]
    (update-application command
      {:verdicts {$elemMatch {:id verdictId}}}
      {$set {"verdicts.$.kuntalupatunnus" (:kuntalupatunnus verdict)
             "verdicts.$.draft" true
             "verdicts.$.timestamp" created
             "verdicts.$.sopimus" (:sopimus verdict)
             "verdicts.$.paatokset" (:paatokset verdict)}})))

(defn- publish-verdict [{timestamp :created application :application :as command} {:keys [id kuntalupatunnus]}]
  (if-not (ss/blank? kuntalupatunnus)
    (when-let [next-state (sm/verdict-given-state application)]
      (let [doc-updates (doc-transformations/get-state-transition-updates command next-state)]
        (update-application command
                            (util/deep-merge
                             (:mongo-query doc-updates)
                             {:verdicts {$elemMatch {:id id}}})
                            (util/deep-merge
                             (:mongo-updates doc-updates)
                             (application/state-transition-update next-state timestamp (:user command))
                             {$set {:verdicts.$.draft false}}))
        (t/mark-app-and-attachments-final! (:id application) timestamp)
        (ok)))
    (fail :error.no-verdict-municipality-id)))

(defcommand publish-verdict
  {:parameters [id verdictId]
   :input-validators [(partial action/non-blank-parameters [:id :verdictId])]
   :states     give-verdict-states
   :pre-checks [application-has-verdict-given-state]
   :notified   true
   :on-success (notify :application-verdict)
   :user-roles #{:authority}}
  [{:keys [application] :as command}]
  (if-let [verdict (find-verdict application verdictId)]
    (publish-verdict command verdict)
    (fail :error.unknown)))

(defcommand delete-verdict
  {:parameters [id verdictId]
   :input-validators [(partial action/non-blank-parameters [:id :verdictId])]
   :states     give-verdict-states
   :user-roles #{:authority}}
  [{:keys [application created] :as command}]
  (when-let [verdict (find-verdict application verdictId)]
    (let [target {:type "verdict", :id verdictId} ; key order seems to be significant!
          is-verdict-attachment? #(= (select-keys (:target %) [:id :type]) target)
          attachments (filter is-verdict-attachment? (:attachments application))
          {:keys [sent state verdicts]} application
          ; Deleting the only given verdict? Return sent or submitted state.
          step-back? (and (= 1 (count verdicts)) (states/verdict-given-states (keyword state)))
          updates (merge {$pull {:verdicts {:id verdictId}
                                 :comments {:target target}
                                 :tasks {:source target}}}
                    (when step-back? {$set {:state (if (and sent (sm/valid-state? application :sent)) :sent :submitted)}}))]
      (update-application command updates)
      (doseq [{attachment-id :id} attachments]
        (attachment/delete-attachment application attachment-id))
      (when step-back?
        (notifications/notify! :application-state-change command)))))

(defcommand sign-verdict
  {:description "Applicant/application owner can sign an application's verdict"
   :parameters [id verdictId password]
   :input-validators [(partial action/non-blank-parameters [:id :verdictId :password])]
   :states     states/post-verdict-states
   :pre-checks [domain/validate-owner-or-write-access]
   :user-roles #{:applicant :authority}}
  [{:keys [application created user] :as command}]
  (if (user/get-user-with-password (:username user) password)
    (when (find-verdict application verdictId)
      (update-application command
                          {:verdicts {$elemMatch {:id verdictId}}}
                          {$set  {:modified              created}
                           $push {:verdicts.$.signatures {:created created
                                                          :user (user/summary user)}}
                          }))
    (do
      ; Throttle giving information about incorrect password
      (Thread/sleep 2000)
      (fail :error.password))))
