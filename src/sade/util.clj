(ns sade.util
  (:require [clojure.walk :refer [postwalk prewalk]]
            [clojure.string :refer [join]]
            [sade.strings :refer [numeric? decimal-number?] :as ss]
            [clj-time.format :as timeformat]
            [clj-time.coerce :as tc]
            [schema.core :as sc])
  (:import [org.joda.time LocalDateTime]))

(defn postwalk-map
  "traverses m and applies f to all maps within"
  [f m] (postwalk (fn [x] (if (map? x) (into {} (f x)) x)) m))

(defn prewalk-map
  "traverses m and applies f to all maps within"
  [f m] (prewalk (fn [x] (if (map? x) (into {} (f x)) x)) m))

(defn convert-values
  "Runs a recursive conversion"
  ([m f]
    (postwalk-map (partial map (fn [[k v]] [k (f v)])) m))
  ([m pred f]
    (postwalk-map (partial map (fn [[k v]] (if (pred k v) [k (f v)] [k v]))) m)))

(defn strip-empty-maps
  "removes recursively all keys from map which have empty map as value"
  [m] (postwalk-map (partial filter (comp (partial not= {}) val)) m))

(defn strip-nils
  "removes recursively all keys from map which have value of nil"
  [m] (postwalk-map (partial filter (comp not nil? val)) m))

; from clojure.contrib/core

(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn select
  "Takes a map and a vector of keys, returns a vector of values from map."
  [m [k & ks]]
  (when k
    (cons (get m k) (select m ks))))

(defn some-key
  "Tries given keys and returns the first non-nil value from map m"
  [m & ks]
  (let [k (first ks)]
    (when (and m k)
      (if (nil? (m k))
        (apply some-key m (rest ks))
        (m k)))))


; From clojure.contrib/seq

(defn indexed
  "Returns a lazy sequence of [index, item] pairs, where items come
  from 's' and indexes count up from zero.

  (indexed '(a b c d))  =>  ([0 a] [1 b] [2 c] [3 d])"
  [s]
  (map vector (iterate inc 0) s))

(defn positions
  "Returns a lazy sequence containing the positions at which pred
   is true for items in coll."
  [pred coll]
  (for [[idx elt] (indexed coll) :when (pred elt)] idx))

; From clojure.contrib/map-utils)
(defn deep-merge-with
  "Like merge-with, but merges maps recursively, applying the given fn
   only when there's a non-map at a particular level.

  (deep-merge-with + {:a {:b {:c 1 :d {:x 1 :y 2}} :e 3} :f 4}
                     {:a {:b {:c 2 :d {:z 9} :z 3} :e 100}})
  -> {:a {:b {:z 3, :c 3, :d {:z 9, :x 1, :y 2}}, :e 103}, :f 4}"
  [f & maps]
  (apply
    (fn m [& maps]
      (if (every? map? maps)
        (apply merge-with m maps)
        (apply f maps)))
    (filter (comp not nil?) maps)))

(defn deep-merge
  "Merges maps recursively using deep-merge-with:
   leaf values from the later maps win conflicts."
  [& maps]
  (apply deep-merge-with (fn [_ x] x) maps))

(defn contains-value? [coll checker]
  (if (coll? coll)
    (if (empty? coll)
      false
      (let [values (if (map? coll) (vals coll) coll)]
        (or
          (contains-value? (first values) checker)
          (contains-value? (rest values) checker))))
    (if checker
      (checker coll)
      false)))

(defn ->int
  "Reads a integer from input. Returns default if not a integer.
   Default default is 0"
  ([x] (->int x 0))
  ([x default]
    (try
      (Integer/parseInt (cond
                          (keyword? x) (name x)
                          (number? x)  (str (int x))
                          :else        (str x)))
      (catch Exception e
        default))))

(defn ->double [v]
  (let [s (str v)]
    (if (or (numeric? s) (decimal-number? s)) (Double/parseDouble s) 0.0)))

(defn abs [n]
  {:pre [(number? n)]}
  (Math/abs n))

(defmacro fn-> [& body] `(fn [x#] (-> x# ~@body)))
(defmacro fn->> [& body] `(fn [x#] (->> x# ~@body)))

;; https://gist.github.com/rplevy/3021378

(defmacro with
  "do things with the first expression passed,
   and produce the result"
  [expr & body]
  `(let [~'% ~expr] ~@body))

(defmacro within
  "do things with the first expression passed (for side effects),
   but produce the value of the first expression"
  [expr & body]
  `(let [~'% ~expr] ~@body ~'%))

(defmacro future* [& body]
  `(future
     (try
       ~@body
       (catch Throwable e#
         (println (format "unhandled exception in future at %s:%d: %s" *file* ~(-> &form meta :line) e#))
         (.printStackTrace e#)
         (throw e#)))))

(defn missing-keys
  "Returns seq of keys from 'required-keys' that are not present or have nil value in 'src-map', or
   nil if all required keys are present. If 'required-keys' is nil, returns nil. Not lazy."
  [src-map required-keys]
  (assert required-keys "required-keys is required (no pun intended)")
  (seq (reduce
         (fn [missing k] (if (nil? (get src-map k)) (cons k missing) missing))
         ()
         required-keys)))

(defn- local-date-time [^Long timestamp]
  (LocalDateTime. timestamp))

(defn to-local-date [^Long timestamp]
  (when timestamp
    (let [dt (local-date-time timestamp)]
      (timeformat/unparse-local (timeformat/formatter "dd.MM.YYYY") dt))))

(defn to-local-datetime [^Long timestamp]
  (when timestamp
    (let [dt (local-date-time timestamp)]
      (timeformat/unparse-local (timeformat/formatter "dd.MM.yyyy HH:mm") dt))))

(defn to-xml-date [^Long timestamp]
  (when timestamp
    (let [dt (tc/from-long timestamp)]
      (timeformat/unparse (timeformat/formatter "YYYY-MM-dd") dt))))

(defn to-xml-datetime [^Long timestamp]
  (when timestamp
    (let [dt (tc/from-long timestamp)]
      (timeformat/unparse (timeformat/formatter "YYYY-MM-dd'T'HH:mm:ss") dt))))

(defn to-xml-date-from-string [^String date-as-string]
  (when date-as-string
    (let [d (timeformat/parse-local-date (timeformat/formatter "dd.MM.YYYY" ) date-as-string)]
      (timeformat/unparse-local-date (timeformat/formatter "YYYY-MM-dd") d))))

(defn to-xml-datetime-from-string [^String date-as-string]
  (when date-as-string
    (let [d (timeformat/parse-local (timeformat/formatter "dd.MM.YYYY" ) date-as-string)]
      (timeformat/unparse-local-date (timeformat/formatter "YYYY-MM-dd'T'HH:mm:ssZ") d))))

(defn to-millis-from-local-date-string [^String date-as-string]
  (when date-as-string
    (let [d (timeformat/parse (timeformat/formatter "dd.MM.YYYY" ) date-as-string)]
      (tc/to-long d))))

(def time-pattern #"^([012]?[0-9]):([0-5]?[0-9])(:([0-5][0-9])(\.(\d))?)?$")

(defn to-xml-time-from-string [^String time-s]
  (when-let [matches (and time-s (seq (filter #(and % (Character/isDigit (first %))) (rest (re-matches time-pattern time-s)))))]
    (let [fmt (case (count matches)
                2 "%02d:%02d:00"
                3 "%02d:%02d:%02d"
                4 "%02d:%02d:%02d.%d")]
      (apply format fmt (map ->int matches)))))

(def property-id-pattern
  "Regex for property id human readable format"
  #"^(\d{1,3})-(\d{1,3})-(\d{1,4})-(\d{1,4})$")

(defn to-property-id [^String human-readable]
  (let [parts (map #(Integer/parseInt % 10) (rest (re-matches property-id-pattern human-readable)))]
    (apply format "%03d%03d%04d%04d" parts)))

(def human-readable-property-id-pattern
  "Regex for splitting db-saved property id to human readable form"
  #"^([0-9]{1,3})([0-9]{1,3})([0-9]{1,4})([0-9]{1,4})$"
  )

(defn to-human-readable-property-id [property-id]
  (->> (re-matches human-readable-property-id-pattern property-id)
       (rest)
       (map read-string)
       (join "-")))

(defn valid-email? [email]
  (try
    (javax.mail.internet.InternetAddress. email)
    (boolean (re-matches #".+@.+\..+" email))
    (catch Exception _
      false)))

(defn sequable?
  "Returns true if x can be converted to sequence."
  [x]
  (or (seq? x)
      (instance? clojure.lang.Seqable x)
      (instance? Iterable x)
      (instance? java.util.Map x)
      (string? x)
      (nil? x)
      (-> x .getClass .isArray)))

(defn empty-or-nil?
  "Returns true if x is either nil or empty if it's sequable."
  [x]
  (or (nil? x) (and (sequable? x) (empty? x))))

(defn not-empty-or-nil? [x] (not (empty-or-nil? x)))

(defn boolean? [x] (instance? Boolean x))

(defn assoc-when
  "Assocs entries with not-empty-or-nil values into m."
  [m & kvs]
  (into m (filter #(->> % val not-empty-or-nil?) (apply hash-map kvs))))

(defn finnish-y? [y]
  (if-let [[_ number check] (re-matches #"FI(\d{7})-(\d)" y)]
    (let [cn (mod (reduce + (map * [7 9 10 5 8 4 2] (map #(Long/parseLong (str %)) number))) 11)
          cn (if (zero? cn) 0 (- 11 cn))]
      (= (Long/parseLong check) cn))))

(defn y? [y]
  (cond
    (nil? y)              false
    (.startsWith y "FI")  (finnish-y? y)
    :else                 (re-matches #"[A-Z]{2}.+" y)))

(defn finnish-ovt? [ovt]
  (if-let [[_ y c] (re-matches #"0037(\d{7})(\d)\d{0,5}" ovt)]
    (finnish-y? (str "FI" y \- c))))

(defn ovt? [ovt]
  (cond
    (nil? ovt)                false
    (.startsWith ovt "0037")  (finnish-ovt? ovt)
    :else                     (re-matches #"\d{4}.+" ovt)))

;;
;; Schema utils:
;;

(def min-length (memoize
                  (fn [min-len]
                    (sc/pred
                      (fn [v]
                        (>= (count v) min-len))
                      (str "Shorter than " min-len)))))

(def max-length (memoize
                  (fn [max-len]
                    (sc/pred
                      (fn [v]
                        (<= (count v) max-len))
                      (str "Longer than " max-len)))))

(defn min-length-string [min-len]
  (sc/both sc/Str (min-length min-len)))

(defn max-length-string [max-len]
  (sc/both sc/Str (max-length max-len)))

(defn exclude-from-sequence
  "Removes the items in the sequential given as the second parameter from the sequential given as the first parameter"
  [orig-seq exclude-seq]
  {:pre [(and (sequential? orig-seq) (sequential? exclude-seq))]}
  (let [exclude-set (set exclude-seq)]
    (remove #(exclude-set %) orig-seq)))
