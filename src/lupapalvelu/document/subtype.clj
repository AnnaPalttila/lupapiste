(ns lupapalvelu.document.subtype
  (:use [clojure.string :only [blank?]]
        [clojure.tools.logging])
  (:require [sade.util :refer [safe-int]]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]))

(defmulti subtype-validation (fn [elem _] (keyword (:subtype elem))))

(defmethod subtype-validation :email [_ v]
  (cond
    (blank? v) nil
    (re-matches #".+@.+\..+" v) nil
    :else [:warn "illegal-email"]))

(defmethod subtype-validation :tel [_ v]
  (cond
    (blank? v) nil
    (re-matches #"^\+?[\d\s-]+" v) nil
    :else [:warn "illegal-tel"]))

(defmethod subtype-validation :number [{:keys [min max]} v]
  (when-not (blank? v)
    (let [min-int  (safe-int min (java.lang.Integer/MIN_VALUE))
          max-int  (safe-int max (java.lang.Integer/MAX_VALUE))
          number   (safe-int v nil)]
      (when-not (and number (<= min-int number max-int))
        [:warn "illegal-number"]))))

(defmethod subtype-validation :digit [_ v]
  (cond
    (blank? v) nil
    (re-matches #"^\d$" v) nil
    :else [:warn "illegal-number"]))

(defmethod subtype-validation :letter [{:keys [case]} v]
  (let [regexp (condp = case
                 :lower #"^\p{Ll}$"
                 :upper #"^\p{Lu}$"
                 #"^\p{L}$")]
    (cond
      (blank? v) nil
      (re-matches regexp v) nil
      :else [:warn (str "illegal-letter:" (if case (name case) "any"))])))

(defmethod subtype-validation :kiinteistotunnus [_ v]
  (cond
    (blank? v) nil
    (re-matches #"^\d{14}$" v) nil
    :else [:warn "illegal-kiinteistotunnus"]))

(defmethod subtype-validation :zip [_ v]
  (cond
    (blank? v) nil
    (re-matches #"^\d{5}$" v) nil
    :else [:warn "illegal-zip"]))

(defn- validate-hetu-date [hetu]
  (let [dateparsts (rest (re-find #"^(\d{2})(\d{2})(\d{2})([aA+-]).*" hetu))
        yy (last (butlast dateparsts))
        yyyy (str (case (last dateparsts) "+" "18" "-" "19" "20") yy)
        basic-date (str yyyy (second dateparsts) (first dateparsts))]
    (try
      (tf/parse (tf/formatters :basic-date) basic-date)
      nil
      (catch Exception e
        [:warn "illegal-hetu"]))))

(defn- validate-hetu-checksum [hetu]
  (let [number   (Long/parseLong (str (subs hetu 0 6) (subs hetu 7 10)))
        n (mod number 31)
        checksum  (nth ["0" "1" "2" "3" "4" "5" "6" "7" "8" "9" "A" "B" "C" "D" "E""F" "H" "J" "K" "L" "M" "N" "P" "R" "S" "T" "U" "V" "W" "X" "Y"] n)
        old-checksum (subs hetu 10 11)]
    (when (not= checksum old-checksum) [:warn "illegal-hetu"])))

(defmethod subtype-validation :hetu [_ v]
  (cond
    (blank? v) nil
    (re-matches #"^(0[1-9]|[12]\d|3[01])(0[1-9]|1[0-2])([5-9]\d+|\d\d-|[01]\dA)\d{3}[\dA-Z]$" v) (or (validate-hetu-date v) (validate-hetu-checksum v))
    :else [:warn "illegal-hetu"]))

(defmethod subtype-validation nil [_ _]
  nil)

(defmethod subtype-validation :default [elem _]
  (error "Unknown subtype:" elem)
  [:err "illegal-subtype"])
