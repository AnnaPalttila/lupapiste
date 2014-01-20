(ns lupapalvelu.document.tools
  (:require [clojure.walk :as walk]))

(defn nil-values [_] nil)

(defn type-verifier [{:keys [type] :as element}]
  (when-not (keyword? type) (throw (RuntimeException. (str "Invalid type: " element)))))

(defn missing [element]
  (throw (UnsupportedOperationException. (str element))))

(defn dummy-values [{:keys [type subtype case name body] :as element}]
  (condp = (keyword type)
    :text             "text"
    :checkbox         true
    :date             "2.5.1974"
    :select           (-> body first :name)
    :radioGroup       (-> body first :name)
    :personSelector   "123"
    :buildingSelector "001"
    :newBuildingSelector "1"
    :string           (condp = (keyword subtype)
                        :maaraala-tunnus   "0003"
                        :email            "example@example.com"
                        :tel              "012 123 4567"
                        :number           "4"
                        :digit            "1"
                        :kiinteistotunnus "09100200990013"
                        :zip              "33800"
                        :hetu             "210281-9988"
                        :vrk-address      "Ranta\"tie\" 66:*"
                        :vrk-name         "Ilkka"
                        :y-tunnus         "2341528-4"
                        :rakennusnumero   "001"
                        nil               "string"
                        :letter           (condp = (keyword case)
                                            :lower "a"
                                            :upper "A"
                                            nil    "Z"
                                            (missing element))
                        (missing element))
    (missing element)))

;;
;; Internal
;;

(defn- ^{:testable true} flattened [col]
  (walk/postwalk
    #(if (and (sequential? %) (-> % first map?))
       (into {} %)
       %)
    col))

(defn- ^{:testable true} group [x]
  (if (:repeating x)
    {:name :0
     :type :group
     :body (:body x)}
    (:body x)))

(defn- ^{:testable true} create [{body :body} f]
  (walk/prewalk
    #(if (map? %)
       (let [k (-> % :name keyword)
             v (if (= :group (-> % :type keyword)) (group %) (f %))]
         {k v})
       %)
    body))

;;
;; Public api
;;

(defn wrapped
  "Wraps leaf values in a map and under k key, key defaults to :value.
   Assumes that every key in the original map is a keyword."
  ([m] (wrapped m :value))
  ([m k]
    (walk/postwalk
      (fn [x] (if (or (keyword? x) (coll? x)) x {k x}))
      m)))

(defn unwrapped
  "(unwrapped (wrapped original)) => original"
  ([m] (unwrapped m :value))
  ([m k]
    (assert (keyword? k))
    (walk/postwalk
      (fn [x] (if (and (map? x) (contains? x k)) (k x) x))
      m)))

(defn timestamped
  "Assocs timestamp besides every value-key"
  ([m timestamp] (timestamped m timestamp :value :modified))
  ([m timestamp value-key timestamp-key]
  (walk/postwalk
    (fn [x] (if (and (map? x) (contains? x value-key)) (assoc x timestamp-key timestamp) x))
    m)))

(defn create-document-data
  "Creates document data from schema using function f as input-creator. f defaults to 'nil-values'"
  ([schema]
    (create-document-data schema nil-values))
  ([schema f]
    (->
      schema
      (create f)
      flattened
      wrapped)))

(defn path-vals
  "Returns vector of tuples containing path vector to the value and the value."
  [m]
  (letfn
    [(pvals [l p m]
       (reduce
         (fn [l [k v]]
           (if (map? v)
             (pvals l (conj p k) v)
             (cons [(conj p k) v] l)))
         l m))]
    (pvals [] [] m)))

(defn assoc-in-path-vals
  "Re-created a map from it's path-vals extracted with (path-vals)."
  [c] (reduce (partial apply assoc-in) {} c))

(defn schema-body-without-element-by-name
  "returns a schema body with all elements with name of element-name stripped of."
  [schema-body element-name]
  (walk/postwalk
    (fn [form]
      (cond
        (and (map? form) (= (:name form) element-name)) nil
        (sequential? form) (->> form (filter identity) vec)
        :else form))
    schema-body))

(defn schema-without-element-by-name
  "returns a copy of a schema with all elements with name of element-name stripped of."
  [schema element-name]
  (update-in schema [:body] schema-body-without-element-by-name element-name))

(defn deep-find
  "Finds 0..n locations in the m structured where target is found.
   Target can be a single key or any deep vector of keys.
   Returns list of vectors that first value contains key to location and second val is value found in."
  ([m target]
    (deep-find m target [] []))
  ([m target current-location result]
    (let [target (if (sequential? target) target [target])]
      (if (get-in m target)
        (conj result [current-location (get-in m target)])
        (reduce concat (for [[k v] m]
                         (when (map? v) (if-not (contains? v :value)
                                          (concat result (deep-find v target (conj current-location k) result))
                                          result))))))))

