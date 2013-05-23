(ns lupapalvelu.document.vrk
  (:use [lupapalvelu.clojure15]
        [lupapalvelu.document.validator])
  (:require [sade.util :refer [->int fn->]]
            [clojure.string :as s]))

;;
;; da lib
;;

(defmacro defvalidator-old [validator-name doc bindings & body]
  `(swap! validators assoc (keyword ~validator-name)
     {:doc ~doc
      :fn (fn [~@bindings] (do ~@body))}))

(defn exists? [x] (-> x s/blank? not))

;;
;; Data
;;

(def kayttotarkoitus->tilavuus {:011 10000
                                :012 15000
                                :511 200000
                                :013 9000
                                :021 10000
                                :022 10000
                                :521 250000
                                :032 100000
                                :039 150000
                                :041 2500
                                :111 150000
                                :112 1500000
                                :119 1000000
                                :121 200000
                                :123 200000
                                :124 10000
                                :129 20000
                                :131 100000
                                :139 40000
                                :141 40000
                                :151 500000
                                :161 1000000
                                :162 1100000
                                :163 500000
                                :164 100000
                                :169 100000
                                :211 600000
                                :213 250000
                                :214 100000
                                :215 120000
                                :219 100000
                                :221 100000
                                :222 50000
                                :223 50000
                                :229 100000
                                :231 20000
                                :239 100000
                                :241 50000
                                :311 300000
                                :312 30000
                                :322 200000
                                :323 200000
                                :324 1500000
                                :331 50000
                                :341 50000
                                :342 50000
                                :349 25000
                                :351 200000
                                :352 200000
                                :353 260000
                                :354 800000
                                :359 300000
                                :369 300000
                                :531 700000
                                :532 200000
                                :541 100000
                                :549 100000
                                :611 1200000
                                :613 700000
                                :691 3000000
                                :692 800000
                                :699 2000000
                                :711 1700000
                                :712 1500000
                                :719 1000000
                                :721 50000
                                :722 250000
                                :723 50000
                                :729 100000
                                :811 50000
                                :819 50000
                                :891 500000
                                :892 200000
                                :893 100000
                                :899 40000
                                :931 4000
                                :941 50000
                                :999 50000})
;;
;; validators
;;

(defn ->kayttotarkoitus [x]
  (some->> x (re-matches #"(\d+) .*") last keyword ))

(defn ->huoneistoala [huoneistot]
  (reduce + (map (fn-> second :huoneistonTyyppi :huoneistoala ->int) huoneistot)))

;; FIME: please implement
(defn asuinrakennus? [data]
  true)

(defvalidator-old "vrk:CR327"
  "k\u00e4ytt\u00f6tarkoituksen mukainen maksimitilavuus"
  [{{{schema-name :name} :info} :schema data :data}]
  (when (= schema-name "uusiRakennus")
    (let [kayttotarkoitus (some->> data :kaytto :kayttotarkoitus :value ->kayttotarkoitus)
          tilavuus        (some->> data :mitat :tilavuus :value ->int)
          max-tilavuus    (kayttotarkoitus->tilavuus kayttotarkoitus)]
      (when (and tilavuus max-tilavuus (> tilavuus max-tilavuus))
        [{:path[:kaytto :kayttotarkoitus]
          :result [:warn "vrk:CR327"]}
         {:path[:mitat :tilavuus]
          :result [:warn "vrk:CR327"]}]))))

(defvalidator-old "vrk:BR106"
  "Puutalossa saa olla korkeintaan 4 kerrosta"
  [{{{schema-name :name} :info} :schema data :data}]
  (when
    (and
      (= schema-name "uusiRakennus")
      (some-> data :rakenne :kantavaRakennusaine :value (= "puu"))
      (some-> data :mitat :kerrosluku :value ->int (> 4)))
    [{:path[:rakenne :kantavaRakennusaine]
      :result [:warn "vrk:BR106"]}
     {:path[:mitat :kerrosluku]
      :result [:warn "vrk:BR106"]}]))

(defvalidator-old "vrk:CR343"
  "Jos lammitustapa on 3 (sahkolammitys), on polttoaineen oltava 4 (sahko)"
  [{{{schema-name :name} :info} :schema data :data}]
  (when
    (and
      (= schema-name "uusiRakennus")
      (some-> data :lammitys :lammitystapa :value (= "suorasahk\u00f6"))
      (some-> data :lammitys :lammonlahde :value (not= "s\u00e4hk\u00f6")))
    [{:path [:lammitys :lammitystapa]
      :result [:warn "vrk:CR343"]}
     {:path [:lammitys :lammonlahde]
      :result [:warn "vrk:CR343"]}]))

(defvalidator-old "vrk:CR342"
  "Sahko polttoaineena vaatii sahkoliittyman"
  [{{{schema-name :name} :info} :schema data :data}]
  (when
    (and
      (= schema-name "uusiRakennus")
      (some-> data :lammitys :lammonlahde :value (= "s\u00e4hk\u00f6"))
      (some-> data :verkostoliittymat :sahkoKytkin :value not))
    [{:path [:lammitys :lammonlahde]
      :result [:warn "vrk:CR342"]}
     {:path [:verkostoliittymat :sahkoKytkin]
      :result [:warn "vrk:CR342"]}]))

(defvalidator-old "vrk:CR341"
  "Sahkolammitus vaatii sahkoliittyman"
  [{{{schema-name :name} :info} :schema data :data}]
  (when
    (and
      (= schema-name "uusiRakennus")
      (some-> data :lammitys :lammitystapa :value (= "suorasahk\u00f6"))
      (some-> data :verkostoliittymat :sahkoKytkin :value not))
    [{:path [:lammitys :lammitystapa]
      :result [:warn "vrk:CR341"]}
     {:path [:verkostoliittymat :sahkoKytkin]
      :result [:warn "vrk:CR341"]}]))

(defvalidator-old "vrk:CR336"
  "Jos lammitystapa on 5 (ei kiinteaa lammitystapaa), ei saa olla polttoainetta"
  [{{{schema-name :name} :info} :schema data :data}]
  (when
    (and
      (= schema-name "uusiRakennus")
      (some-> data :lammitys :lammitystapa :value (= "eiLammitysta"))
      (some-> data :lammitys :lammonlahde :value exists?))
    [{:path [:lammitys :lammitystapa]
      :result [:warn "vrk:CR336"]}
     {:path [:lammitys :lammonlahde]
      :result [:warn "vrk:CR336"]}]))

(defvalidator-old "vrk:CR335"
  "Jos lammitystapa ei ole 5 (ei kiinteaa lammitystapaa), on polttoaine ilmoitettava"
  [{{{schema-name :name} :info} :schema data :data}]
  (when
    (and
      (= schema-name "uusiRakennus")
      (some-> data :lammitys :lammitystapa :value exists?)
      (some-> data :lammitys :lammitystapa :value (not= "eiLammitysta"))
      (some-> data :lammitys :lammonlahde :value exists? not))
    [{:path [:lammitys :lammitystapa]
      :result [:warn "vrk:CR335"]}
     {:path [:lammitys :lammonlahde]
      :result [:warn "vrk:CR335"]}]))

;;
;; new stuff
;;

(defvalidator :vrk:CR326
  {:doc    "Kokonaisalan oltava vahintaan kerrosala"
   :schema "uusiRakennus"
   :fields [kokonaisala [:mitat :kokonaisala ->int]
            kerrosala   [:mitat :kerrosala ->int]]
   :facts  {:ok   [[10 10]]
            :fail [[10 11]]}}
  (and kokonaisala kerrosala (< kokonaisala kerrosala)))

(defvalidator :vrk:CR324
  {:doc    "Sahko polttoaineena vaatii varusteeksi sahkon"
   :schema "uusiRakennus"
   :fields [polttoaine [:lammitus :lammonlahde]
            sahko      [:varusteet :sahkoKytkin]]}
  (and (= polttoaine "s\u00e4hk\u00f6") (not= sahko true)))

(defvalidator :vrk:CR322
  {:doc    "Uuden rakennuksen kokonaisalan oltava vahintaan huoneistoala"
   :schema "uusiRakennus"
   :fields [kokonaisala  [:mitat :kokonaisala ->int]
            huoneistoala [:huoneistot ->huoneistoala]]
   :facts  {:ok   [[100 {:0 {:huoneistonTyyppi {:huoneistoala 60}}
                         :1 {:huoneistonTyyppi {:huoneistoala 40}}}]]
            :fail [[100 {:0 {:huoneistonTyyppi {:huoneistoala 60}}
                         :1 {:huoneistonTyyppi {:huoneistoala 50}}}]]}}
  (and kokonaisala huoneistoala (< kokonaisala huoneistoala)))

(defvalidator :vrk:BR113
  {:doc    "Pien- tai rivitalossa saa olla korkeintaan 3 kerrosta"
   :schema "uusiRakennus"
   :fields [kayttotarkoitus [:kaytto :kayttotarkoitus ->kayttotarkoitus]
            kerrosluku      [:mitat :kerrosluku ->int]]
   :facts  {:ok   [["011 yhden asunnon talot" 3]]
            :fail [["011 yhden asunnon talot" 4]]}}
  (and (#{:011 :012 :013 :021 :022} kayttotarkoitus) (> kerrosluku 3)))

(defvalidator :vrk:CR328:sahko
  {:doc    "Verkostoliittymat ja rakennuksen varusteet tasmattava: Sahko"
   :schema "uusiRakennus"
   :fields [liittyma [:verkostoliittymat :sahkoKytkin]
            varuste  [:varusteet         :sahkoKytkin]]
   :facts   {:ok   [[true true]]
             :fail [[true false]]}}
  (and liittyma (not varuste)))

(defvalidator :vrk:CR328:viemari
  {:doc    "Verkostoliittymat ja rakennuksen varusteet tasmattava: Viemari"
   :schema "uusiRakennus"
   :fields [liittyma [:verkostoliittymat :viemariKytkin]
            varuste  [:varusteet         :viemariKytkin]]
   :facts  {:ok   [[true true]]
            :fail [[true false]]}}
  (and liittyma (not varuste)))

(defvalidator :vrk:CR328:vesijohto
  {:doc    "Verkostoliittymat ja rakennuksen varusteet tasmattava: Vesijohto"
   :schema "uusiRakennus"
   :fields [liittyma [:verkostoliittymat :vesijohtoKytkin]
            varuste  [:varusteet         :vesijohtoKytkin]]
   :facts   {:ok   [[true true]]
             :fail [[true false]]}}
  (and liittyma (not varuste)))

(defvalidator :vrk:CR312
  {:doc     "Jos rakentamistoimenpide on 691 tai 111, on kerrosluvun oltava 1"
   :schema  "uusiRakennus"
   :fields  [toimenpide [:kaytto :kayttotarkoitus ->kayttotarkoitus]
             kerrosluku [:mitat :kerrosluku ->int]]
   :facts   {:ok   [["111 myym\u00e4l\u00e4hallit" 1]]
             :fail [["111 myym\u00e4l\u00e4hallit" 2]]}}
  (and (#{:691 :111} toimenpide) (not= kerrosluku 1)))

(defvalidator :vrk:CR313
  {:doc     "Jos rakentamistoimenpide on 1, niin tilavuuden on oltava 1,5 kertaa kerrosala. &BR407 kayttotarkoitukset"
   :schema  "uusiRakennus"
   :fields  [tilavuus        [:mitat :tilavuus ->int]
             kerrosala       [:mitat :kerrosala ->int]
             kayttotarkoitus [:kaytto :kayttotarkoitus ->kayttotarkoitus]]
   :facts   {:ok   [[6 4 "611 voimalaitosrakennukset"]
                    [5 4 "032 luhtitalot"]]
             :fail [[5 4 "611 voimalaitosrakennukset"]]}}
  (and
    tilavuus
    (< tilavuus (* 1.5 kerrosala))
    (and
      kayttotarkoitus
      (or
        (> (->int kayttotarkoitus) 799)
        (#{:162 :163 :169 :611 :613 :699 :712 :719 :722} kayttotarkoitus)))))

(defvalidator :vrk:CR314
  {:doc     "Asuinrakennukssa pitaa olla lammitys"
   :schema  "uusiRakennus"
   :fields  [kayttotarkoitus [:kaytto :kayttotarkoitus ->kayttotarkoitus ->int]
             lammitystapa    [:lammitys :lammitystapa]]
   :facts   {:ok   [["032 luhtitalot" "ilmakeskus"]]
             :fail [["032 luhtitalot" "eiLammitysta"]]}}
  (and
    (<= 11 kayttotarkoitus 39)
    (not (#{"vesikeskus" "ilmakeskus" "suorasahk\u00f6" "uuni"} lammitystapa))))

(defvalidator :vrk:CR315
  {:doc     "Omakotitalossa pitaa olla huoneisto"
   :schema  "uusiRakennus"
   :fields  [kayttotarkoitus [:kaytto :kayttotarkoitus ->kayttotarkoitus]
             huoneistot      [:huoneistot keys count]]
   :facts   {:ok   [["011 yhden asunnon talot" {}]] ;; nop -> has 1 huoneisto
             :fail [["011 yhden asunnon talot" {:6 {:any :any}}]]}} ;; add another huoneisto
  (and (= :011 kayttotarkoitus) (not= 1 huoneistot)))

(defvalidator :vrk:CR316
  {:doc     "Paritalossa pitaa olla kaksi uutta huoneistoa"
   :schema  "uusiRakennus"
   :fields  [kayttotarkoitus [:kaytto :kayttotarkoitus ->kayttotarkoitus]
             huoneistot      [:huoneistot keys count]]
   :facts   {:ok   [["012 kahden asunnon talot" {:6 {:any :any}}]]
             :fail [["012 kahden asunnon talot" {}]]}}
  (and (= :012 kayttotarkoitus) (not= 2 huoneistot)))

(defvalidator :vrk:CR317
  {:doc     "Rivi- tai kerrostaloissa tulee olla vahintaan kolme uutta huoneistoa"
   :schema  "uusiRakennus"
   :fields  [kayttotarkoitus [:kaytto :kayttotarkoitus ->kayttotarkoitus ->int]
             huoneistot      [:huoneistot keys count]]
   :facts   {:ok   [["032 luhtitalot" {:1 {:any :any}
                                       :2 {:any :any}
                                       :3 {:any :any}}]]
             :fail [["032 luhtitalot" {}]]}}
  (and (<= 13 kayttotarkoitus 39) (< huoneistot 3)))

(defvalidator :vrk:CR319
  {:doc     "Jos rakentamistoimenpide on 1 ja kayttotarkoitus on 032 - 039, on kerrosluvun oltava vahintaan 2"
   :schema  "uusiRakennus"
   :fields  [kayttotarkoitus [:kaytto :kayttotarkoitus ->kayttotarkoitus ->int]
             kerrosluku      [:mitat :kerrosluku ->int]]
   :facts   {:ok   [["032 luhtitalot" 2]]
             :fail [["032 luhtitalot" 1]]}}
  (and (<= 32 kayttotarkoitus 39) (< kerrosluku 2)))

;; Tommi's stuff here

(defvalidator :vrk:CR340
  {:doc     "Asuinrakennuksessa kerrosalan on oltava vahintaaan 7 neliota"
   :schema  "uusiRakennus"
   :fields  [kerrosala [:mitat :kerrosala ->int]]
   :facts   {:ok   [[7]]
             :fail [[6]]}}
  (and (asuinrakennus? data) (< kerrosala 7)))

(defvalidator :vrk:CR333:tilavuus
  {:doc     "Jos rakentamistoimenpide on 1, ovat tilavuus,kerrosala,kokonaisala ja kerrosluku pakollisia"
   :schema  "uusiRakennus"
   :fields  [tilavuus [:mitat :tilavuus ->int]]
   :facts   {:ok   [[10]]
             :fail [[0]]}}
  (= tilavuus 0))

(defvalidator :vrk:CR333:kerrosala
  {:doc     "Jos rakentamistoimenpide on 1, ovat tilavuus,kerrosala,kokonaisala ja kerrosluku pakollisia"
   :schema  "uusiRakennus"
   :fields  [kerrosala [:mitat :kerrosala ->int]]
   :facts   {:ok   [[10]]
             :fail [[0]]}}
  (= kerrosala 0))

(defvalidator :vrk:CR333:kokonaisala
  {:doc     "Jos rakentamistoimenpide on 1, ovat tilavuus,kerrosala,kokonaisala ja kerrosluku pakollisia"
   :schema  "uusiRakennus"
   :fields  [kokonaisala [:mitat :kokonaisala ->int]]
   :facts   {:ok   [[10]]
             :fail [[0]]}}
  (= kokonaisala 0))

(defvalidator :vrk:CR333:kerrosluku
  {:doc     "Jos rakentamistoimenpide on 1, ovat tilavuus,kerrosala,kokonaisala ja kerrosluku pakollisia"
   :schema  "uusiRakennus"
   :fields  [kerrosluku [:mitat :kerrosluku ->int]]
   :facts   {:ok   [[10]]
             :fail [[0]]}}
  (= kerrosluku 0))

;; Juha's stuff here

(comment
  (require '[lupapalvelu.document.vrk-test :refer [check-validator]])
  (check-validator (@validators :vrk:CR319))
  )
