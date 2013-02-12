(ns lupapalvelu.document.tools
  (:require [clojure.walk :as walk]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.document.model :as model]))

(def osoite (schemas/schemas "osoite"))
(def rakennus (schemas/schemas "uusiRakennus"))

(defn nil-values [_] nil)

(defn dummy-values [{:keys [type name body]}]
  (condp = type
    :select           (-> body first :name)
    :checkbox         true
    :number           "42"
    :email            "example@example.com"
    :tel              "012 123 4567"
    :letter           "\u00c5"
    :kiinteistotunnus "09100200990013"
    name))

(defn flattened [col]
  (->> col
    (walk/postwalk
      (fn [x]
        (if (and (vector? x) (-> x first map?))
          (into {} x)
          x)))))

(defn create [{body :body} f]
  (->> body
    (walk/prewalk
      (fn [x]
        (if (map? x)
          (let [k (-> x :name keyword)
                v (if (= :group (:type x))
                    (if (:repeating x)
                      {:name :0
                       :type :group
                       :body (dissoc x :repeating)}
                      (:body x))
                    (f x))]
            {k v})
          x)))))
