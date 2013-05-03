(ns lupapalvelu.document.model
  (:use [clojure.tools.logging]
        [sade.strings]
        [lupapalvelu.document.schemas :only [schemas]]
        [clojure.walk :only [keywordize-keys]])
  (:require [clojure.string :as s]
            [clj-time.format :as timeformat]
            [lupapalvelu.document.subtype :as subtype]))

;;
;; Validation:
;;
;; if you changes this value, change it in docgen.js, too
(def default-max-len 255)

(defmulti validate (fn [elem _] (keyword (:type elem))))

(defmethod validate :group [_ v]
  (if (not (map? v)) [:err "illegal-value:not-a-map"]))

(defmethod validate :string [{:keys [max-len min-len] :as elem} v]
  (cond
    (not= (type v) String) [:err "illegal-value:not-a-string"]
    (> (.length v) (or max-len default-max-len)) [:err "illegal-value:too-long"]
    (< (.length v) (or min-len 0)) [:warn "illegal-value:too-short"]
    :else (subtype/subtype-validation elem v)))

(defmethod validate :text [elem v]
  (cond
    (not= (type v) String) [:err "illegal-value:not-a-string"]
    (> (.length v) (or (:max-len elem) default-max-len)) [:err "illegal-value:too-long"]
    (< (.length v) (or (:min-len elem) 0)) [:warn "illegal-value:too-short"]))

(defmethod validate :checkbox [_ v]
  (if (not= (type v) Boolean) [:err "illegal-value:not-a-boolean"]))


(def dd-mm-yyyy (timeformat/formatter "dd.MM.YYYY"))

(defmethod validate :date [elem v]
  (try
    (or (s/blank? v) (timeformat/parse dd-mm-yyyy v))
    nil
    (catch Exception e [:warn "invalid-date-format"])))


(defmethod validate :select [elem v] nil)
(defmethod validate :radioGroup [elem v] nil)
(defmethod validate :buildingSelector [elem v] nil)
(defmethod validate :personSelector [elem v] nil)

(defmethod validate nil [_ _]
  [:err "illegal-key"])

(defmethod validate :default [elem _]
  (warn "Unknown schema type: elem=[%s]" elem)
  [:err "unknown-type"])

;;
;; Neue api:
;;
(defn- find-by-name [schema-body [k & ks]]
  (when-let [elem (some #(when (= (:name %) k) %) schema-body)]
    (if (nil? ks)
      elem
      (if (:repeating elem)
        (when (numeric? (first ks))
          (if (seq (rest ks))
            (find-by-name (:body elem) (rest ks))
            elem))
        (find-by-name (:body elem) ks)))))

(defn- validate-update [schema-body results [k v]]
  (let [elem   (keywordize-keys (find-by-name schema-body (s/split k #"\.")))
        result (validate elem v)]
    (if (nil? result)
      results
      (conj results (cons k result)))))

(defn- validate-document-fields [schema-body k v path]
  (let [current-path (if k (conj path (name k)) path)]
    (if (contains? v :value)
      (let [elem (find-by-name schema-body current-path)
            result (validate (keywordize-keys elem) (:value v))]
        (when-not (nil? result) (println k v path elem result))
        (nil? result))
      (every? true? (map (fn [[k2 v2]] (validate-document-fields schema-body k2 v2 current-path)) v)))))

(defn validate-against-current-schema [document]
  (let [schema-name (get-in document [:schema :info :name])
        schema-body (:body (get schemas schema-name))
        document-data (:data document)]
    (if document-data
      (validate-document-fields schema-body nil document-data [])
      (do
        (println "No data")
        false))))

;;
;; the newest
;;

(defn- validate-fields [schema-body k v path]
  (let [current-path (if k (conj path (name k)) path)]
    (if (contains? v :value)
      (let [element (find-by-name schema-body current-path)
            result  (validate (keywordize-keys element) (:value v))]
        (and result {:data v
                     :path (vec (map keyword current-path))
                     :element element
                     :result result}))
      (filter
        (comp not nil?)
        (map (fn [[k2 v2]]
               (validate-fields schema-body k2 v2 current-path)) v)))))

(defn validate-document
  "validates document against it's local schema and returns list of errors."
  [{{{schema-name :name} :info schema-body :body} :schema document-data :data}]
  (when document-data
    (let [errors (flatten (validate-fields schema-body nil document-data []))]
      (when (not-empty errors) errors))))

(defn validate-against-current-schema
  "validates document against the latest schema and returns list of errors."
  [{{{schema-name :name} :info} :schema document-data :data :as document}]
  (let [latest-schema-body (:body (get schemas schema-name))
        pimped-document    (assoc-in document [:schema :body] latest-schema-body)]
    (validate-document pimped-document)))

;;
;; /the newest
;;

(defn validate-updates
  "Validate updates against schema.

  Updates is expected to be a seq of updates, where each update is a key/value seq. Key is name of
  the element to update, and the value is a new value for element. Key should be dot separated path.

  Returns a seq of validation failures. Each failure is a seq of three elements. First element is the
  name of the element. Second element is either :warn or :err and finally, the last element is the
  warning or error message."
  [schema updates]
  (reduce (partial validate-update (:body schema)) [] updates))

(defn validation-status
  "Accepts validation results (as defined in 'validate-updates' function) and returns either :ok
  (when results is empty), :warn (when results contains only warnings) or :err (when results
  contains one or more errors)."
  [results]
  (cond
    (empty? results) :ok
    (some #(= (second %) :err) results) :err
    :else :warn))
