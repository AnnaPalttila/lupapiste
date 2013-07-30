(ns lupapalvelu.document.schemas)

;;
;; Register schemas
;;

(defonce ^:private all-schemas (atom {}))
(defn get-schemas [] @all-schemas)
(defn defschema [data]
  (let [schema-name (name (get-in data [:info :name]))]
    (swap! all-schemas assoc schema-name (assoc-in data [:info :name] schema-name))))
(defn defschemas [schemas]
  (doseq [schema schemas]
    (defschema schema)))

;;
;; helpers
;;

(defn body
  "Shallow merges stuff into vector"
  [& rest]
  (reduce
    (fn [a x]
      (let [v (if (sequential? x) x (vector x))]
        (concat a v)))
    [] rest))

(defn repeatable
  "Created repeatable element."
  [name childs]
  [{:name      name
    :type      :group
    :repeating true
    :body      (body childs)}])

(defn to-map-by-name
  "Take list of schema maps, return a map of schemas keyed by :name under :info"
  [docs]
  (reduce (fn [docs doc] (assoc docs (get-in doc [:info :name]) doc)) {} docs))

;;
;; schema sniplets
;;

(def kuvaus {:name "kuvaus" :type :text :max-len 4000 :required true :layout :full-width})

(def henkilo-valitsin [{:name "userId" :type :personSelector}
                       {:name "turvakieltoKytkin" :type :checkbox}])

(def rakennuksen-valitsin [{:name "rakennusnro" :type :buildingSelector}])

(def simple-osoite [{:name "osoite"
                     :type :group
                     :body [{:name "katu" :type :string :subtype :vrk-address :required true}
                            {:name "postinumero" :type :string :subtype :zip :size "s" :required true}
                            {:name "postitoimipaikannimi" :type :string :subtype :vrk-address :size "m" :required true}]}])

(def full-osoite [{:name "osoite"
                   :type :group
                   :body [{:name "kunta" :type :string}
                          {:name "lahiosoite" :type :string}
                          {:name "osoitenumero" :type :string :subtype :number :min 0 :max 9999}
                          {:name "osoitenumero2" :type :string}
                          {:name "jakokirjain" :type :string :subtype :letter :case :lower :max-len 1 :size "s"}
                          {:name "jakokirjain2" :type :string :size "s"}
                          {:name "porras" :type :string :subtype :letter :case :upper :max-len 1 :size "s"}
                          {:name "huoneisto" :type :string :size "s"}
                          {:name "postinumero" :type :string :subtype :zip :size "s"}
                          {:name "postitoimipaikannimi" :type :string :size "m"}
                          {:name "pistesijanti" :type :string}]}])

(def yhteystiedot [{:name "yhteystiedot"
                    :type :group
                    :body [{:name "puhelin" :type :string :subtype :tel :required true}
                           {:name "email" :type :string :subtype :email :required true}
                           #_{:name "fax" :type :string :subtype :tel}]}])

(def yhteystiedot-public-area [{:name "yhteystiedot"
                                :type :group
                                :body [{:name "puhelin" :type :string :subtype :tel :required true}
                                       {:name "email" :type :string :subtype :email :required true}
                                       #_{:name "fax" :type :string :subtype :tel}]}])        ;; TODO: Saako FAX jaada? Ei kryspissa?

(def henkilotiedot-minimal [{:name "henkilotiedot"
                             :type :group
                             :body [{:name "etunimi" :type :string :subtype :vrk-name :required true}
                                    {:name "sukunimi" :type :string :subtype :vrk-name :required true}]}])

(def henkilotiedot-with-hetu {:name "henkilotiedot"
                               :type :group
                               :body [{:name "etunimi" :type :string :subtype :vrk-name :required true}
                                      {:name "sukunimi" :type :string :subtype :vrk-name :required true}
                                      {:name "hetu" :type :string :subtype :hetu :max-len 11 :required true}]})

(def henkilo (body
               henkilo-valitsin
               [henkilotiedot-with-hetu]
               simple-osoite
               yhteystiedot))

(def henkilo-public-area (body
                           {:name "userId" :type :personSelector}
                           [henkilotiedot-with-hetu]
                           simple-osoite
                           yhteystiedot-public-area))

(def henkilo-with-required-hetu (body
                                  henkilo-valitsin
                                  [(assoc henkilotiedot-with-hetu
                                     :body
                                     (map (fn [ht] (if (= (:name ht) "hetu") (merge ht {:required true}) ht))
                                       (:body henkilotiedot-with-hetu)))]
                                  simple-osoite
                                  yhteystiedot))

(def yritys-minimal [{:name "yritysnimi" :type :string :required true}
                     {:name "liikeJaYhteisoTunnus" :type :string :subtype :y-tunnus :required true}])

(def yritys (body
              yritys-minimal
              simple-osoite
              {:name "yhteyshenkilo"
               :type :group
               :body (body
                       henkilotiedot-minimal
                       yhteystiedot)}))

(def yritys-public-area (body
                          yritys-minimal
                          simple-osoite
                          {:name "vastuuhenkilo"
                           :type :group
                           :body (body
                                   henkilotiedot-minimal
                                   yhteystiedot-public-area)}))

(def party [{:name "_selected" :type :radioGroup :body [{:name "henkilo"} {:name "yritys"}]}
            {:name "henkilo" :type :group :body henkilo}
            {:name "yritys" :type :group :body yritys}])

(def party-public-area [{:name "_selected" :type :radioGroup :body [{:name "henkilo"} {:name "yritys"}]}
                        {:name "henkilo" :type :group :body henkilo-public-area}
                        {:name "yritys" :type :group :body yritys-public-area}])

(def party-with-required-hetu (body
                                [{:name "_selected" :type :radioGroup :body [{:name "henkilo"} {:name "yritys"}]}
                                 {:name "henkilo" :type :group :body henkilo-with-required-hetu}
                                 {:name "yritys" :type :group :body yritys}]))


(def patevyys [{:name "koulutus" :type :string :required true}
               {:name "patevyysluokka" :type :select :required true
                :body [{:name "AA"}
                       {:name "A"}
                       {:name "B"}
                       {:name "C"}
                       {:name "ei tiedossa"}]}])

(def designer-basic (body
                      henkilotiedot-minimal
                      {:name "yritys" :type :group :body yritys-minimal}
                      simple-osoite
                      yhteystiedot))

(def paasuunnittelija (body
                        henkilo-valitsin
                        designer-basic
                        {:name "patevyys" :type :group :body patevyys}))

(def kuntaroolikoodi [{:name "kuntaRoolikoodi" :type :select
                       :body [{:name "GEO-suunnittelija"}
                              {:name "LVI-suunnittelija"}
                              {:name "IV-suunnittelija"}
                              {:name "KVV-suunnittelija"}
                              {:name "RAK-rakennesuunnittelija"}
                              {:name "ARK-rakennussuunnittelija"}
                              {:name "Vaikeiden t\u00F6iden suunnittelija"}
                              {:name "ei tiedossa"}]}])

(def suunnittelija (body
                     henkilo-valitsin
                     designer-basic
                     {:name "patevyys" :type :group
                      :body (body
                              kuntaroolikoodi
                              patevyys)}))

(def huoneisto [{:name "huoneistoTunnus" :type :group
                 :body [{:name "porras" :type :string :subtype :letter :case :upper :max-len 1 :size "s"}
                        {:name "huoneistonumero" :type :string :subtype :number :min-len 1 :max-len 3 :size "s"}
                        {:name "jakokirjain" :type :string :subtype :letter :case :lower :max-len 1 :size "s"}]}
                {:name "huoneistonTyyppi"
                 :type :group
                 :body [{:name "huoneistoTyyppi" :type :select
                         :body [{:name "asuinhuoneisto"}
                                {:name "toimitila"}
                                {:name "ei tiedossa"}]}
                        {:name "huoneistoala" :type :string :unit "m2" :subtype :number :size "s" :min 1 :max 9999999 :required true}
                        {:name "huoneluku" :type :string :subtype :number :min 1 :max 99 :required true :size "s"}]}
                {:name "keittionTyyppi" :type :select :required true
                 :body [{:name "keittio"}
                        {:name "keittokomero"}
                        {:name "keittotila"}
                        {:name "tupakeittio"}
                        {:name "ei tiedossa"}]}
                {:name "varusteet"
                 :type :group
                 :layout :vertical
                 :body [{:name "WCKytkin" :type :checkbox}
                        {:name "ammeTaiSuihkuKytkin" :type :checkbox}
                        {:name "saunaKytkin" :type :checkbox}
                        {:name "parvekeTaiTerassiKytkin" :type :checkbox}
                        {:name "lamminvesiKytkin" :type :checkbox}]}])

(def yhden-asunnon-talot "011 yhden asunnon talot")
(def vapaa-ajan-asuinrakennus "041 vapaa-ajan asuinrakennukset")
(def talousrakennus "941 talousrakennukset")
(def rakennuksen-kayttotarkoitus [{:name yhden-asunnon-talot}
                                  {:name "012 kahden asunnon talot"}
                                  {:name "013 muut erilliset talot"}
                                  {:name "021 rivitalot"}
                                  {:name "022 ketjutalot"}
                                  {:name "032 luhtitalot"}
                                  {:name "039 muut asuinkerrostalot"}
                                  {:name vapaa-ajan-asuinrakennus}
                                  {:name "111 myym\u00e4l\u00e4hallit"}
                                  {:name "112 liike- ja tavaratalot, kauppakeskukset"}
                                  {:name "119 muut myym\u00e4l\u00e4rakennukset"}
                                  {:name "121 hotellit yms"}
                                  {:name "123 loma-, lepo- ja virkistyskodit"}
                                  {:name "124 vuokrattavat lomam\u00f6kit ja -osakkeet"}
                                  {:name "129 muut majoitusliikerakennukset"}
                                  {:name "131 asuntolat yms"}
                                  {:name "139 muut asuntolarakennukset"}
                                  {:name "141 ravintolat yms"}
                                  {:name "151 toimistorakennukset"}
                                  {:name "161 rautatie- ja linja-autoasemat, lento- ja satamaterminaalit"}
                                  {:name "162 kulkuneuvojen suoja- ja huoltorakennukset"}
                                  {:name "163 pys\u00e4k\u00f6intitalot"}
                                  {:name "164 tietoliikenteen rakennukset"}
                                  {:name "169 muut liikenteen rakennukset"}
                                  {:name "211 keskussairaalat"}
                                  {:name "213 muut sairaalat"}
                                  {:name "214 terveyskeskukset"}
                                  {:name "215 terveydenhuollon erityislaitokset"}
                                  {:name "219 muut terveydenhuoltorakennukset"}
                                  {:name "221 vanhainkodit"}
                                  {:name "222 lasten- ja koulukodit"}
                                  {:name "223 kehitysvammaisten hoitolaitokset"}
                                  {:name "229 muut huoltolaitosrakennukset"}
                                  {:name "231 lasten p\u00e4iv\u00e4kodit"}
                                  {:name "239 muualla luokittelemattomat sosiaalitoimen rakennukset"}
                                  {:name "241 vankilat"}
                                  {:name "311 teatterit, ooppera-, konsertti- ja kongressitalot"}
                                  {:name "312 elokuvateatterit"}
                                  {:name "322 kirjastot ja arkistot"}
                                  {:name "323 museot ja taidegalleriat"}
                                  {:name "324 n\u00e4yttelyhallit"}
                                  {:name "331 seura- ja kerhorakennukset yms"}
                                  {:name "341 kirkot, kappelit, luostarit ja rukoushuoneet"}
                                  {:name "342 seurakuntatalot"}
                                  {:name "349 muut uskonnollisten yhteis\u00f6jen rakennukset"}
                                  {:name "351 j\u00e4\u00e4hallit"}
                                  {:name "352 uimahallit"}
                                  {:name "353 tennis-, squash- ja sulkapallohallit"}
                                  {:name "354 monitoimihallit ja muut urheiluhallit"}
                                  {:name "359 muut urheilu- ja kuntoilurakennukset"}
                                  {:name "369 muut kokoontumisrakennukset"}
                                  {:name "511 yleissivist\u00e4vien oppilaitosten rakennukset"}
                                  {:name "521 ammatillisten oppilaitosten rakennukset"}
                                  {:name "531 korkeakoulurakennukset"}
                                  {:name "532 tutkimuslaitosrakennukset"}
                                  {:name "541 j\u00e4rjest\u00f6jen, liittojen, ty\u00f6nantajien yms opetusrakennukset"}
                                  {:name "549 muualla luokittelemattomat opetusrakennukset"}
                                  {:name "611 voimalaitosrakennukset"}
                                  {:name "613 yhdyskuntatekniikan rakennukset"}
                                  {:name "691 teollisuushallit"}
                                  {:name "692 teollisuus- ja pienteollisuustalot"}
                                  {:name "699 muut teollisuuden tuotantorakennukset"}
                                  {:name "711 teollisuusvarastot"}
                                  {:name "712 kauppavarastot"}
                                  {:name "719 muut varastorakennukset"}
                                  {:name "721 paloasemat"}
                                  {:name "722 v\u00e4est\u00f6nsuojat"}
                                  {:name "729 muut palo- ja pelastustoimen rakennukset"}
                                  {:name "811 navetat, sikalat, kanalat yms"}
                                  {:name "819 el\u00e4insuojat, ravihevostallit, maneesit yms"}
                                  {:name "891 viljankuivaamot ja viljan s\u00e4ilytysrakennukset"}
                                  {:name "892 kasvihuoneet"}
                                  {:name "893 turkistarhat"}
                                  {:name "899 muut maa-, mets\u00e4- ja kalatalouden rakennukset"}
                                  {:name "931 saunarakennukset"}
                                  {:name talousrakennus}
                                  {:name "999 muualla luokittelemattomat rakennukset"}
                                  {:name "ei tiedossa"}])
(def rakennuksen-tiedot [{:name "kaytto"
                          :type :group
                          :body [{:name "rakentajaTyyppi" :type :select :required true
                                  :body [{:name "liiketaloudellinen"}
                                         {:name "muu"}
                                         {:name "ei tiedossa"}]}
                                 {:name "kayttotarkoitus" :type :select
                                  :body rakennuksen-kayttotarkoitus}]}
                         {:name "mitat"
                          :type :group
                          :body [{:name "tilavuus" :type :string :size "s" :unit "m3" :subtype :number :min 1 :max 9999999}
                                 {:name "kerrosala" :type :string :size "s" :unit "m2" :subtype :number :min 1 :max 9999999}
                                 {:name "kokonaisala" :type :string :size "s" :unit "m2" :subtype :number :min 1 :max 9999999}
                                 {:name "kerrosluku" :type :string :size "s" :subtype :number :min 0 :max 50}
                                 {:name "kellarinpinta-ala" :type :string :size "s" :unit "m2" :subtype :number :min 1 :max 9999999}]}
                         {:name "rakenne"
                          :type :group
                          :body [{:name "rakentamistapa" :type :select :required true
                                  :body [{:name "elementti"}
                                         {:name "paikalla"}
                                         {:name "ei tiedossa"}]}
                                 {:name "kantavaRakennusaine" :type :select :required true
                                  :body [{:name "betoni"}
                                         {:name "tiili"}
                                         {:name "ter\u00e4s"}
                                         {:name "puu"}
                                         {:name "ei tiedossa"}]}
                                 {:name "muuRakennusaine" :type :string}
                                 {:name "julkisivu" :type :select
                                  :body [{:name "betoni"}
                                         {:name "tiili"}
                                         {:name "metallilevy"}
                                         {:name "kivi"}
                                         {:name "puu"}
                                         {:name "lasi"}
                                         {:name "ei tiedossa"}]}
                                 {:name "muuMateriaali" :type :string}]}
                         {:name "lammitys"
                          :type :group
                          :body [{:name "lammitystapa" :type :select
                                  :body [{:name "vesikeskus"}
                                         {:name "ilmakeskus"}
                                         {:name "suorasahk\u00f6"}
                                         {:name "uuni"}
                                         {:name "eiLammitysta"}
                                         {:name "ei tiedossa"}]}
                                 {:name "lammonlahde" :type :select :required true
                                  :body [{:name "kauko tai aluel\u00e4mp\u00f6"}
                                         {:name "kevyt poltto\u00f6ljy"}
                                         {:name "raskas poltto\u00f6ljy"}
                                         {:name "s\u00e4hk\u00f6"}
                                         {:name "kaasu"}
                                         {:name "kiviihiili koksi tms"}
                                         {:name "turve"}
                                         {:name "maal\u00e4mp\u00f6"}
                                         {:name "puu"}
                                         {:name "muu" :type :string :size "s"}
                                         {:name "ei tiedossa"}]}
                                 {:name "muu-lammonlahde" :type :string}]}
                         {:name "verkostoliittymat" :type :group :layout :vertical
                          :body [{:name "viemariKytkin" :type :checkbox}
                                 {:name "vesijohtoKytkin" :type :checkbox}
                                 {:name "sahkoKytkin" :type :checkbox}
                                 {:name "maakaasuKytkin" :type :checkbox}
                                 {:name "kaapeliKytkin" :type :checkbox}]}
                         {:name "varusteet" :type :group :layout :vertical
                          :body [{:name "sahkoKytkin" :type :checkbox}
                                 {:name "kaasuKytkin" :type :checkbox}
                                 {:name "viemariKytkin" :type :checkbox}
                                 {:name "vesijohtoKytkin" :type :checkbox}
                                 {:name "hissiKytkin" :type :checkbox}
                                 {:name "koneellinenilmastointiKytkin" :type :checkbox}
                                 {:name "lamminvesiKytkin" :type :checkbox}
                                 {:name "aurinkopaneeliKytkin" :type :checkbox}
                                 {:name "saunoja" :type :string :subtype :number :min 1 :max 99 :size "s" :unit "kpl"}
                                 {:name "vaestonsuoja" :type :string :subtype :number :min 1 :max 99999 :size "s" :unit "hengelle"}]}
                         {:name "luokitus"
                          :type :group
                          :body [{:name "energialuokka" :type :select
                                  :body [{:name "A"}
                                         {:name "B"}
                                         {:name "C"}
                                         {:name "D"}
                                         {:name "E"}
                                         {:name "F"}
                                         {:name "G"}]}
                                 {:name "energiatehokkuusluku" :type :string :size "s" :subtype :number}
                                 {:name "energiatehokkuusluvunYksikko" :type :select
                                  :body [{:name "kWh/m2"}
                                         {:name "kWh/brm2/vuosi"}]}
                                 {:name "paloluokka" :type :select
                                  :body [{:name "palonkest\u00e4v\u00e4"}
                                          {:name "paloapid\u00e4tt\u00e4v\u00e4"}
                                          {:name "paloahidastava"}
                                          {:name "l\u00e4hinn\u00e4 paloakest\u00e4v\u00e4"}
                                          {:name "l\u00e4hinn\u00e4 paloapid\u00e4tt\u00e4v\u00e4"}
                                          {:name "l\u00e4hinn\u00e4 paloahidastava"}
                                          {:name "P1"}
                                          {:name "P2"}
                                          {:name "P3"}
                                          {:name "P1/P2"}
                                          {:name "P1/P3"}
                                          {:name "P2/P3"}
                                          {:name "P1/P2/P3"}]}]}
                         {:name "huoneistot"
                          :type :group
                          :repeating true
                          :approvable true
                          :body huoneisto}])


(def rakennelma (body [{:name "kokonaisala" :type :string :size "s" :unit "m2" :subtype :number}] kuvaus))
(def maisematyo (body kuvaus))

(def rakennuksen-omistajat [{:name "rakennuksenOmistajat"
                             :type :group
                             :repeating true
                             :approvable true
                             :body (body party-with-required-hetu
                                         [{:name "omistajalaji" :type :select
                                           :body [{:name "yksityinen maatalousyritt\u00e4j\u00e4"}
                                                  {:name "muu yksityinen henkil\u00f6 tai perikunta"}
                                                  {:name "asunto-oy tai asunto-osuuskunta"}
                                                  {:name "kiinteist\u00f6 oy"}
                                                  {:name "yksityinen yritys (osake-, avoin- tai kommandiittiyhti\u00f6, osuuskunta)"}
                                                  {:name "valtio- tai kuntaenemmist\u00f6inen yritys"}
                                                  {:name "kunnan liikelaitos"}
                                                  {:name "valtion liikelaitos"}
                                                  {:name "pankki tai vakuutuslaitos"}
                                                  {:name "kunta tai kuntainliitto"}
                                                  {:name "valtio"}
                                                  {:name "sosiaaliturvarahasto"}
                                                  {:name "uskonnollinen yhteis\u00f6, s\u00e4\u00e4ti\u00f6, puolue tai yhdistys"}
                                                  {:name "ei tiedossa"}]}
                                                {:name "muu-omistajalaji" :type :string}])}])

(def muumuutostyo "muut muutosty\u00f6t")
(def perustusten-korjaus "perustusten ja kantavien rakenteiden muutos- ja korjausty\u00f6t")
(def kayttotarkotuksen-muutos "rakennukse p\u00e4\u00e4asiallinen k\u00e4ytt\u00f6tarkoitusmuutos")

(def muutostyonlaji [{:name "perusparannuskytkin" :type :checkbox}
                     {:name "muutostyolaji" :type :select :required true
                      :body
                      [{:name perustusten-korjaus}
                       {:name kayttotarkotuksen-muutos}
                       {:name muumuutostyo}]}])

(def olemassaoleva-rakennus (body
                              rakennuksen-valitsin
                              rakennuksen-omistajat
                              full-osoite
                              rakennuksen-tiedot))

(def rakennuksen-muuttaminen (body
                               muutostyonlaji
                               olemassaoleva-rakennus))

(def rakennuksen-laajentaminen (body [{:name "laajennuksen-tiedot"
                                       :type :group
                                       :body [{:name "perusparannuskytkin" :type :checkbox}
                                              {:name "mitat"
                                               :type :group
                                               :body [{:name "tilavuus" :type :string :size "s" :unit "m3" :subtype :number :min 1 :max 9999999}
                                                      {:name "kerrosala" :type :string :size "s" :unit "m2" :subtype :number :min 1 :max 9999999}
                                                      {:name "kokonaisala" :type :string :size "s" :unit "m2" :subtype :number :min 1 :max 9999999}
                                                      {:name "huoneistoala" :type :group :repeating true :removable true
                                                       :body [{:name "pintaAla" :type :string :size "s" :unit "m2" :subtype :number :min 1 :max 9999999}
                                                              {:name "kayttotarkoitusKoodi" :type :select
                                                               :body [{:name "asuntotilaa(ei vapaa-ajan asunnoista)"}
                                                                      {:name "myym\u00e4l\u00e4, majoitus- ja ravitsemustilaa"}
                                                                      {:name "hoitotilaa"}
                                                                      {:name "toimisto- ja hallintotilaa"}
                                                                      {:name "kokoontumistilaa"}
                                                                      {:name "opetustilaa"}
                                                                      {:name "tuotantotilaa(teollisuus)"}
                                                                      {:name "varastotilaa"}
                                                                      {:name "muuta huoneistoalaan kuuluvaa tilaa"}
                                                                      {:name "ei tiedossa"}]}]}]}]}]
                                     olemassaoleva-rakennus))

(def purku (body
             {:name "poistumanSyy" :type :select
              :body [{:name "purettu uudisrakentamisen vuoksi"}
                     {:name "purettu muusta syyst\u00e4"}
                     {:name "tuhoutunut"}
                     {:name "r\u00e4nsitymisen vuoksi hyl\u00e4tty"}
                     {:name "poistaminen"}]}
             {:name "poistumanAjankohta" :type :date}
             olemassaoleva-rakennus))

;;
;; schemas
;;

(defschemas
  [{:info {:name "hankkeen-kuvaus"
           :order 1}
    :body [kuvaus
           {:name "poikkeamat" :type :text :max-len 4000 :layout :full-width}]}

    {:info {:name "uusiRakennus" :approvable true}
     :body (body rakennuksen-omistajat rakennuksen-tiedot)}

    {:info {:name "rakennuksen-muuttaminen"}
     :body rakennuksen-muuttaminen}

    {:info {:name "rakennuksen-laajentaminen"}
     :body rakennuksen-laajentaminen}

    {:info {:name "purku"}
     :body purku}

    {:info {:name "kaupunkikuvatoimenpide"}
     :body rakennelma}

    {:info {:name "maisematyo"}
     :body maisematyo}

    {:info {:name "hakija"
            :order 3
            :removable true
            :repeating true
            :type :party}
     :body party}

    {:info {:name "hakija-public-area"
            :order 3
            :removable true
            :repeating true
            :type :party}
     :body party-public-area}

    {:info {:name "paasuunnittelija"
            :order 4
            :removable false
            :type :party}
     :body paasuunnittelija}

    {:info {:name "suunnittelija"
            :repeating true
            :order 5
            :removable true
            :type :party}
     :body suunnittelija}

    {:info {:name "maksaja"
            :repeating true
            :order 6
            :removable true
            :type :party}
     :body (body
             party
             {:name "laskuviite" :type :string :max-len 30 :layout :full-width})}

    {:info {:name "rakennuspaikka"
            :order 2}
     :body [{:name "kiinteisto"
             :type :group
             :body [{:name "maaraalaTunnus" :type :string}
                    {:name "tilanNimi" :type :string :readonly true}
                    {:name "rekisterointipvm" :type :string :readonly true}
                    {:name "maapintaala" :type :string :readonly true :unit "hehtaaria"}
                    {:name "vesipintaala" :type :string :readonly true :unit "hehtaaria"}]}

            {:name "hallintaperuste" :type :select :required true
             :body [{:name "oma"}
                    {:name "vuokra"}
                    {:name "ei tiedossa"}]}
            {:name "kaavanaste" :type :select
             :body [{:name "asema"}
                    {:name "ranta"}
                    {:name "rakennus"}
                    {:name "yleis"}
                    {:name "eiKaavaa"}
                    {:name "ei tiedossa"}]}]}

    {:info {:name "lisatiedot"
            :order 100}
     :body [{:name "suoramarkkinointikielto"
             :type :checkbox
             :layout :full-width}]}])

