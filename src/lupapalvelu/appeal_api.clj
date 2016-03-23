(ns lupapalvelu.appeal-api
  (:require [clojure.set :refer [difference]]
            [sade.core :refer :all]
            [sade.util :as util]
            [sade.schemas :as ssc]
            [schema.core :as sc]
            [monger.operators :refer [$push $pull $elemMatch $set]]
            [lupapalvelu.action :refer [defquery defcommand] :as action]
            [lupapalvelu.appeal :as appeal]
            [lupapalvelu.appeal-verdict :as appeal-verdict]
            [lupapalvelu.states :as states]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.attachment :as attachment]))

(defn- verdict-exists
  "Pre-check to validate that for selected verdictId a verdict exists"
  [{{verdictId :verdictId} :data} {:keys [verdicts]}]
  (when verdictId
    (when-not (util/find-first #(= verdictId (:id %)) verdicts)
      (fail :error.verdict-not-found))))

(defn- appeal-exists
  "Pre-check to validate that at least one appeal exists before appeal verdict can be created"
  [_ {:keys [appeals]}]
  (when (zero? (count appeals))
    (fail :error.appeals-not-found)))

(defn- appeal-id-exists
  "Pre-check to validate that given ID exists in application"
  [{{appeal-id :appealId} :data} {:keys [appeals]}]
  (when appeal-id ; optional parameter, could be nil in command
    (when-not (util/find-by-id appeal-id appeals)
      (fail :error.unknown-appeal))))

(defn- appeal-verdict-id-exists
  "Pre-check to validate that id from parameters exist in :appealVerdicts"
  [{{appeal-id :appealId} :data} {:keys [appealVerdicts]}]
  (when appeal-id
    (when-not (util/find-by-id appeal-id appealVerdicts)
      (fail :error.unknown-appeal-verdict))))

(defn- deny-type-change
  "Pre-check: when appeal is updated, type of appeal can't be changed"
  [{{appeal-id :appealId type :type} :data} {:keys [appeals]}]
  (when appeal-id
    (when-some [appeal (util/find-by-id appeal-id appeals)]
      (when-not (= type (:type appeal))
        (fail :error.appeal-type-change-denied)))))

(defn- latest-for-verdict?
  "True if the appeal-item (the first param) is later than any of the
  appeal-items. The check is limited to the same verdict."
  [{:keys [datestamp target-verdict id]} appeal-items]
  (let [filtered (filter #(and (not= id (:id %))
                               (= target-verdict (:target-verdict %))) appeal-items)]
    (util/is-latest-of? datestamp (map :datestamp filtered))))

(defn- appeal-item-editable?
  "Appeal-item can be either appeal or appealVerdict. Appeal is
  editable if it is later than the latest appealVerdict within the
  same verdict. AppealVerdict is only editable if it is latest
  appeal-item within the verdict."
  [{:keys [appealVerdicts appeals]} appeal-item]
  (let [latest-verdict? (latest-for-verdict? appeal-item appealVerdicts)
        latest-appeal? (latest-for-verdict? appeal-item appeals)]
    (if (= (keyword (:type appeal-item)) :appealVerdict)
      (and latest-verdict? latest-appeal?)
      latest-verdict?)))

(defn- appeal-editable?
  "Pre-check to check that appeal can be edited."
  [{{appeal-id :appealId} :data} {:keys [appeals appealVerdicts] :as application}]
  (when (and appeal-id appealVerdicts)
    (if-let [appeal (util/find-by-id appeal-id appeals)]
      (when-not (appeal-item-editable? application appeal)
        (fail :error.appeal-verdict-already-exists))
      (fail :error.unknown-appeal))))

(defn- appeal-verdict-editable?
  "Pre-check to check that appeal-verdict can be edited."
  [{{appeal-id :appealId} :data} {:keys [appeals appealVerdicts] :as application}]
  (when appeal-id
    (if-let [appeal-verdict (util/find-by-id appeal-id appealVerdicts)]
      (when-not (appeal-item-editable? application appeal-verdict)
        (fail :error.appeal-already-exists))
      (fail :error.unknown-appeal-verdict))))


(defn new-appeal-data-mongo-updates
  "Returns $push mongo update map of data to given 'collection' property.
   Does not validate appeal-data, validation must be taken care elsewhere."
  [collection data]
  {:mongo-updates {$push {(keyword collection) data}}})

(defn update-appeal-data-mongo-updates
  "Generates query and updates for given update-data into given collection.
   Query is $elemMatch to provided matching-id as 'id'.
   Map with mongo query and updates is returned.
   Does not validate appeal-data, validation must be taken care elsewhere."
  [collection matching-id update-data]
  {:mongo-query   {(keyword collection) {$elemMatch {:id matching-id}}}
   :mongo-updates {$set (zipmap
                          (map #(str (name collection) ".$." (name %)) (keys update-data))
                          (vals update-data))}})

(defn- attachment-updates
  [{{:keys [attachments]} :application :as command} appeal-id appeal-type file-ids]
  (when appeal-id
    (let [old-file-ids (->> attachments
                            (filter (fn [{{target-id :id} :target}]
                                      (= target-id appeal-id)))
                            (map (util/fn-> :latestVersion :fileId)))
          new-file-ids     (difference (set file-ids) (set old-file-ids))
          new-attachment-updates (attachment/new-appeal-attachment-updates command appeal-id appeal-type new-file-ids)
          ;removed-file-ids (difference (set old-file-ids) (set file-ids))
          ]
      {:new-updates new-attachment-updates})))

(defcommand upsert-appeal
  {:description "Creates new appeal if appealId is not given. Updates appeal with given parameters if appealId is given"
   :parameters          [id verdictId type appellant datestamp fileIds]
   :optional-parameters [text appealId]
   :user-roles          #{:authority}
   :states              states/post-verdict-states
   :input-validators    [appeal/input-validator
                         (partial action/number-parameters [:datestamp])]
   :pre-checks          [verdict-exists
                         appeal-id-exists
                         appeal-editable?
                         deny-type-change]}
  [command]
  (let [appeal-data (appeal/appeal-data-for-upsert verdictId type appellant datestamp text appealId)]
    (if appeal-data ; if data is valid
      (let [updates (if appealId
                      (update-appeal-data-mongo-updates :appeals appealId appeal-data)
                      (new-appeal-data-mongo-updates :appeals appeal-data))
            {:keys [new-updates]} (attachment-updates command (or (:id appeal-data) appealId) (:type appeal-data) fileIds)]
        ; remove needed files and attachments
        ; update application to files
        (action/update-application
          command
          (:mongo-query updates)
          (util/deep-merge
            (:mongo-updates updates)
            new-updates)))
      (fail :error.invalid-appeal))))

(defcommand upsert-appeal-verdict
  {:parameters          [id verdictId giver datestamp fileIds]
   :optional-parameters [text appealId]
   :user-roles          #{:authority}
   :states              states/post-verdict-states
   :input-validators    [appeal-verdict/input-validator
                         (partial action/number-parameters [:datestamp])]
   :pre-checks          [verdict-exists
                         appeal-exists
                         appeal-verdict-id-exists
                         appeal-verdict-editable?]}
  [command]
  (let [verdict-data (appeal-verdict/appeal-verdict-data-for-upsert verdictId giver datestamp text appealId)]
    (if verdict-data
      (let [updates (if appealId
                      (update-appeal-data-mongo-updates :appealVerdicts appealId verdict-data)
                      (new-appeal-data-mongo-updates :appealVerdicts verdict-data))
            {:keys [new-updates]} (attachment-updates command (or (:id verdict-data) appealId) :appealVerdict fileIds)]
        (action/update-application
          command
          (:mongo-query updates)
          (util/deep-merge
            (:mongo-updates updates)
            new-updates)))
      (fail :error.invalid-appeal-verdict))))

(defcommand delete-appeal
  {:parameters          [id verdictId appealId]
   :user-roles          #{:authority}
   :input-validators    [(partial action/string-parameters [:appealId])]
   :states              states/post-verdict-states
   :pre-checks          [verdict-exists
                         appeal-id-exists
                         appeal-editable?]}
  [command]
  (action/update-application
    command
    {$pull {:appeals {:id appealId}}}))

(defcommand delete-appeal-verdict
  {:parameters          [id verdictId appealId]
   :user-roles          #{:authority}
   :input-validators    [(partial action/string-parameters [:appealId])]
   :states              states/post-verdict-states
   :pre-checks          [verdict-exists
                         appeal-verdict-id-exists
                         appeal-verdict-editable?]}
  [command]
  (action/update-application
    command
    {$pull {:appealVerdicts {:id appealId}}}))

(defn- process-appeal
  "Process appeal for frontend"
  [application appeal-item]
  (assoc appeal-item :editable (appeal-item-editable? application appeal-item)))

(defn- add-attachments [{id :id :as appeal}]
  ; get attacments for appeal here
  appeal)

(defn- validate-output-format
  "Validate output data for frontend. Logs as ERROR in action pipeline."
  [_ response]
  (assert (contains? response :data))
  (let [data (:data response)]
    (assert (not-every? #(sc/check ssc/ObjectIdStr %) (keys data)) "Verdict IDs as ObjectID strings")
    (doseq [appeal (flatten (vals data))] ; Validate appeals/appeal-verdicts against
      (case (keyword (:type appeal))
        :appealVerdict           (sc/validate appeal-verdict/FrontendAppealVerdict appeal)
        (:appeal :rectification) (sc/validate appeal/FrontendAppeal appeal)))))

(defquery appeals
  {:description "Query for frontend, that returns all appeals/appeal verdicts of application in pre-processed form."
   :parameters [id]
   :user-roles #{:authority :applicant}
   :states     states/post-verdict-states
   :on-success validate-output-format}
  [{application :application}]
  (let [appeal-verdicts (map #(assoc % :type "appealVerdict") (:appealVerdicts application))
        all-appeals     (concat (:appeals application) appeal-verdicts)
        processed-appeals (->> all-appeals
                               (map (comp add-attachments
                                          (partial process-appeal application)))
                               (sort-by :datestamp))]
    (ok :data (group-by :target-verdict processed-appeals))))
