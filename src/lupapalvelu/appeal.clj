(ns lupapalvelu.appeal
  (:require [lupapalvelu.mongo :as mongo]
            [monger.operators :refer :all]
            [sade.core :refer :all]
            [sade.schemas :as ssc]
            [schema.core :refer [defschema] :as sc]
            [sade.util :as util]))

(def appeal-types
  "appeal = Valitus, rectification = Oikaisuvaatimus"
  ["appeal" "rectification"])

(defschema Appeal
  "Schema for a verdict appeal."
  {:id                         ssc/ObjectIdStr
   :target-verdict             ssc/ObjectIdStr         ;; refers to verdicts.paatokset.id
   :type                       (apply sc/enum appeal-types)
   :appellant                  sc/Str                  ;; Name of the person who made the appeal
   :made                       ssc/Timestamp           ;; Date of appeal made - defined manually by authority
   (sc/optional-key :text)     sc/Str                  ;; Optional description
   })

(defn create-appeal [target-verdict-id type appellant made text]
  (util/strip-nils
    {:id                  (mongo/create-id)
     :target-verdict      target-verdict-id
     :type                type
     :appellant           appellant
     :made                made
     :text                text}))

(defn appeal-data-for-upsert
  "'Dispatcher' function for appeal data.
   If appealId is given as last parameter, returns validated update data without id.
   If appealId is not given, returns validated appeal data with generated id"
  [target-verdict-id type appellant made text & [appealId]]
  (if appealId
    (let [update-data (dissoc (create-appeal target-verdict-id type appellant made text) :id)]
      (when-not (sc/check Appeal (assoc update-data :id appealId))
        (dissoc update-data :target-verdict)))
    (let [new-data (create-appeal target-verdict-id type appellant made text)]
      (when-not (sc/check Appeal new-data)
        new-data))))

(defn input-validator
  "Input validator for appeal commands. Validates command parameter against Appeal schema."
  [{{:keys [targetId type appellant made text]} :data}]
  (when (sc/check (dissoc Appeal :id)
                  (util/strip-nils {:target-verdict targetId
                                    :type type
                                    :appellant appellant
                                    :made made
                                    :text text}))
    (fail :error.invalid-appeal)))
