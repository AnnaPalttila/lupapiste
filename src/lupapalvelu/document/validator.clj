(ns lupapalvelu.document.validator
  (:require [lupapalvelu.document.tools :as tools]
            [lupapalvelu.clojure15 :refer [some->>]]
            [sade.util :refer [fn->]]))

(defonce validators (atom {}))

(defn validate
  "Runs all validators, returning list of concatenated validation results."
  [document]
  (->>
    validators
    deref
    vals
    (map :fn)
    (map (fn-> (apply [document])))
    (reduce concat)
    (filter (comp not nil?))))

(defn- starting-keywords
  "Returns vector of starting keywords of vector until
   a non-keyword is found. [:a :b even? :c] -> [:a :b]"
  [v]
  (last
    (reduce
      (fn [[stop result] x]
        (if (or stop (not (keyword? x)))
          [true result]
          [false (conj result x)]))
      [false []] v)))

(defmacro defvalidator
  "Macro to create document-level validators. Unwraps data etc."
  [code {:keys [doc schema fields facts]} & body]
  (let [paths (->> fields (partition 2) (map last) (map starting-keywords) vec)]
    `(swap! validators assoc ~code
       {:code ~code
        :doc ~doc
        :paths ~paths
        :schema ~schema
        :facts ~facts
        :fn (fn [{~'data :data {{~'doc-schema :name} :info} :schema}]
              (let [~'data (tools/un-wrapped ~'data)]
                (when (or (not ~schema) (= ~schema ~'doc-schema))
                  (let
                    ~(reduce into
                       (for [[k v] (partition 2 fields)]
                         [k `(some->> ~'data ~@v)]))
                    (try
                      (when-let [resp# (do ~@body)]
                        (map (fn [path#] {:path   path#
                                          :result [:warn ~(name code)]}) ~paths))
                      (catch Exception e#
                        [{:path   []
                          :result [:warn (str "validator")]
                          :reason (str e#)}]))))))})))
