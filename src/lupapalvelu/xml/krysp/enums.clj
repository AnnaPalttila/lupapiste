(ns lupapalvelu.xml.krysp.enums
  (:require [clojure.string :as s]
            [net.cgrand.enlive-html :as e]
            [sade.util :refer [fn->]]
            [lupapalvelu.clojure15 :refer [some->>]]
            [sade.xml :as xml]))

(defn extract [schema name mapper]
  (->>
    (e/select schema [(e/attr= :name name) :xs:restriction :xs:enumeration])
    (map (fn-> :attrs :value mapper))))

(defn ->keyword-value [x]
  [(some->> x (re-matches #"(\d+) .*") last keyword)
   (s/replace x #"\d+\s*" "")])

(defn ->value [x] x)

(defn ->map [x] (into {} x))

(def extractors [["RakennuksenTiedotType" ->keyword-value ->map]
                 ["KatselmuksenLajiType"]
                 ["JulkisivumateriaaliType"]
                 ["LammitystapaType"]
                 ["PolttoaineType"]
                 ["OmistajalajiType"]
                 ["PaloluokkaType"]
                 ["EnergialuokkaType"]
                 ["PurkamisenSyyType"]])

(comment
  (let [schema (xml/parse "./resources/krysp/rakennusvalvonta.xsd")]
    (doseq [[name mapper writer] extractors]
      (println (str "\n" name ":"))
      (clojure.pprint/pprint ((or writer vec) (extract schema name (or mapper ->value)))))))
