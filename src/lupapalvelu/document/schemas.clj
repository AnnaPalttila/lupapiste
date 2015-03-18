(ns lupapalvelu.document.schemas
  (:require [lupapalvelu.document.tools :refer :all]))

;;
;; Register schemas
;;

(defonce ^:private registered-schemas (atom {}))

(defn get-all-schemas [] @registered-schemas)
(defn get-schemas [version] (get @registered-schemas version))

(defn defschema [version data]
  (let [schema-name (name (get-in data [:info :name]))]
    (swap! registered-schemas
      assoc-in
      [version schema-name]
      (-> data
        (assoc-in [:info :name] schema-name)
        (assoc-in [:info :version] version)))))

(defn defschemas [version schemas]
  (doseq [schema schemas]
    (defschema version schema)))

(defn get-schema
  ([{:keys [version name]}] (get-schema version name))
  ([schema-version schema-name]
    {:pre [schema-version schema-name]}
    (get-in @registered-schemas [schema-version (name schema-name)])))



(defn get-latest-schema-version []
  (->> @registered-schemas keys (sort >) first))

;;
;; helpers
;;

(defn body
  "Shallow merges stuff into vector"
  [& rest]
  (reduce
    (fn [a x]
      (let [v (if (sequential? x)
                x
                (vector x))]
        (concat a v)))
    [] rest))

(defn repeatable
  "Created repeatable element."
  [name childs]
  [{:name      name
    :type      :group
    :repeating true
    :body      (body childs)}])

;;
;; schema sniplets
;;

(def select-one-of-key "_selected")

(def turvakielto "turvakieltoKytkin")

(def kuvaus {:name "kuvaus" :type :text :max-len 4000 :required true :layout :full-width})

(def henkilo-valitsin [{:name "userId" :type :personSelector :blacklist [:neighbor]}])

(def rakennuksen-valitsin [{:name "buildingId" :type :buildingSelector :required true :i18nkey "rakennusnro" :other-key "manuaalinen_rakennusnro"}
                           {:name "rakennusnro" :type :string :subtype :rakennusnumero :hidden true}
                           {:name "manuaalinen_rakennusnro" :type :string :subtype :rakennusnumero :i18nkey "manuaalinen_rakennusnro" :labelclass "really-long"}
                           {:name "valtakunnallinenNumero" :type :string  :subtype :rakennustunnus :hidden true}])

(def uusi-rakennuksen-valitsin [{:name "jarjestysnumero" :type :newBuildingSelector :i18nkey "rakennusnro" :required true}
                                {:name "valtakunnallinenNumero" :type :string  :subtype :rakennustunnus :hidden true}
                                {:name "rakennusnro" :type :string :subtype :rakennusnumero :hidden true}
                                {:name "kiinttun" :type :string :subtype :kiinteistotunnus :hidden true}])

(def simple-osoite [{:name "osoite"
                     :type :group
                     :blacklist [turvakielto]
                     :body [{:name "katu" :type :string :subtype :vrk-address :required true}
                            {:name "postinumero" :type :string :subtype :zip :size "s" :required true}
                            {:name "postitoimipaikannimi" :type :string :subtype :vrk-address :size "m" :required true}]}])

(def rakennuksen-osoite [{:name "osoite"
                   :type :group
                   :body [{:name "kunta" :type :string}
                          {:name "lahiosoite" :type :string}
                          {:name "osoitenumero" :type :string :subtype :number :min 0 :max 9999}
                          {:name "osoitenumero2" :type :string}
                          {:name "jakokirjain" :type :string :subtype :letter :case :lower :max-len 1 :size "s" :hidden true :readonly true}
                          {:name "jakokirjain2" :type :string :size "s" :hidden true :readonly true}
                          {:name "porras" :type :string :subtype :letter :case :upper :max-len 1 :size "s" :hidden true :readonly true}
                          {:name "huoneisto" :type :string :size "s" :hidden true :readonly true}
                          {:name "postinumero" :type :string :subtype :zip :size "s"}
                          {:name "postitoimipaikannimi" :type :string :size "m"}]}])

(def yhteystiedot [{:name "yhteystiedot"
                    :type :group
                    :blacklist [:neighbor turvakielto]
                    :body [{:name "puhelin" :type :string :subtype :tel :required true}
                           {:name "email" :type :string :subtype :email :required true}]}])

(def henkilotiedot-minimal {:name "henkilotiedot"
                            :type :group
                            :body [{:name "etunimi" :type :string :subtype :vrk-name :required true}
                                   {:name "sukunimi" :type :string :subtype :vrk-name :required true}
                                   {:name turvakielto :type :checkbox :blacklist [turvakielto]}]})

(def henkilotiedot {:name "henkilotiedot"
                            :type :group
                            :body [{:name "etunimi" :type :string :subtype :vrk-name :required true}
                                   {:name "sukunimi" :type :string :subtype :vrk-name :required true}
                                   {:name "hetu" :type :hetu :max-len 11 :required true :blacklist [:neighbor turvakielto] :emit [:hetuChanged]}
                                   {:name turvakielto :type :checkbox :blacklist [turvakielto]}]})

(def henkilo (body
               henkilo-valitsin
               [henkilotiedot]
               simple-osoite
               yhteystiedot))

(def henkilo-with-required-hetu (body
                                  henkilo-valitsin
                                  [(assoc henkilotiedot
                                     :body
                                     (map (fn [ht] (if (= (:name ht) "hetu") (merge ht {:required true}) ht))
                                       (:body henkilotiedot)))]
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
                       [henkilotiedot-minimal]
                       yhteystiedot)}))

(def verkkolaskutustieto [{:name "ovtTunnus" :type :string :subtype :ovt :min-len 12 :max-len 17}
                          {:name "verkkolaskuTunnus" :type :string}
                          {:name "valittajaTunnus" :type :string}])

(def yritys-with-verkkolaskutustieto (body
                                       yritys
                                       {:name "verkkolaskutustieto"
                                        :type :group
                                        :body (body
                                                verkkolaskutustieto)}))

(defn- henkilo-yritys-select-group
  [& {:keys [default henkilo-body yritys-body] :or {default "henkilo" henkilo-body henkilo yritys-body yritys}}]
  (body
    {:name select-one-of-key :type :radioGroup :body [{:name "henkilo"} {:name "yritys"}] :default default}
    {:name "henkilo" :type :group :body henkilo-body}
    {:name "yritys" :type :group :body yritys-body}))

(def party (henkilo-yritys-select-group))
(def ya-party (henkilo-yritys-select-group :default "yritys"))
(def party-with-required-hetu (henkilo-yritys-select-group :henkilo-body henkilo-with-required-hetu))

(def koulutusvalinta {:name "koulutusvalinta" :type :select :sortBy :displayname :i18nkey "koulutus"
                      :body [{:name "arkkitehti"}
                             {:name "arkkitehtiylioppilas"}
                             {:name "diplomi-insin\u00f6\u00f6ri"}
                             {:name "insin\u00f6\u00f6ri"}
                             {:name "IV-asentaja"}
                             {:name "kirvesmies"}
                             {:name "LV-asentaja"}
                             {:name "LVI-asentaja"}
                             {:name "LVI-insin\u00f6\u00f6ri"}
                             {:name "LVI-teknikko"}
                             {:name "LVI-ty\u00f6teknikko"}
                             {:name "maisema-arkkitehti"}
                             {:name "rakennusammattity\u00f6mies"}
                             {:name "rakennusarkkitehti"}
                             {:name "rakennusinsin\u00f6\u00f6ri"}
                             {:name "rakennusmestari"}
                             {:name "rakennuspiirt\u00e4j\u00e4"}
                             {:name "rakennusteknikko"}
                             {:name "rakennusty\u00f6teknikko"}
                             {:name "sisustusarkkitehti"}
                             {:name "talonrakennusinsin\u00f6\u00f6ri"}
                             {:name "talonrakennusteknikko"}
                             {:name "tekniikan kandidaatti"}
                             {:name "teknikko"}
                             {:name "muu"}]})

(def patevyys [koulutusvalinta
               {:name "koulutus" :type :string :required false :i18nkey "muukoulutus"}
               {:name "valmistumisvuosi" :type :string :subtype :number :min-len 4 :max-len 4 :size "s" :required false}
               {:name "fise" :type :string :required false}
               {:name "patevyys" :type :string :required false}
               {:name "patevyysluokka" :type :select :sortBy nil :required false
                :body [{:name "AA"}
                       {:name "A"}
                       {:name "B"}
                       {:name "C"}
                       {:name "ei tiedossa"}]}
               {:name "kokemus" :type :string :subtype :number :min-len 1 :max-len 2 :size "s" :required false}])

(def designer-basic (body
                      (schema-body-without-element-by-name henkilotiedot turvakielto)
                      {:name "yritys" :type :group
                       :body (clojure.walk/postwalk (fn [c] (if (and (map? c) (contains? c :required))
                                                              (assoc c :required false)
                                                              c)) yritys-minimal)}
                      simple-osoite
                      yhteystiedot))

(def paasuunnittelija (body
                        henkilo-valitsin
                        designer-basic
                        {:name "patevyys" :type :group :body patevyys}))

(def kuntaroolikoodi [{:name "kuntaRoolikoodi"
                       :i18nkey "osapuoli.suunnittelija.kuntaRoolikoodi._group_label"
                       :type :select :sortBy :displayname
                       :body [{:name "GEO-suunnittelija" :i18nkey "osapuoli.suunnittelija.kuntaRoolikoodi.GEO-suunnittelija"}
                              {:name "LVI-suunnittelija" :i18nkey "osapuoli.suunnittelija.kuntaRoolikoodi.LVI-suunnittelija"}
                              {:name "IV-suunnittelija" :i18nkey "osapuoli.suunnittelija.kuntaRoolikoodi.IV-suunnittelija"}
                              {:name "KVV-suunnittelija" :i18nkey "osapuoli.suunnittelija.kuntaRoolikoodi.KVV-suunnittelija"}
                              {:name "RAK-rakennesuunnittelija" :i18nkey "osapuoli.suunnittelija.kuntaRoolikoodi.RAK-rakennesuunnittelija"}
                              {:name "ARK-rakennussuunnittelija" :i18nkey "osapuoli.suunnittelija.kuntaRoolikoodi.ARK-rakennussuunnittelija"}
                              {:name "Vaikeiden t\u00F6iden suunnittelija" :i18nkey "osapuoli.suunnittelija.kuntaRoolikoodi.Vaikeiden t\u00f6iden suunnittelija"}
                              {:name "ei tiedossa" :i18nkey "osapuoli.kuntaRoolikoodi.ei tiedossa"}]}])

(def suunnittelija (body
                     kuntaroolikoodi
                     henkilo-valitsin
                     designer-basic
                     {:name "patevyys" :type :group :body patevyys}))

(def vastattavat-tyotehtavat-tyonjohtaja [{:name "vastattavatTyotehtavat"
                                           :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat._group_label"
                                           :type :group
                                           :layout :vertical
                                           :body [{:name "rakennuksenRakentaminen" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.rakennuksenRakentaminen" :type :checkbox}
                                                  {:name "rakennuksenMuutosJaKorjaustyo" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.rakennuksenMuutosJaKorjaustyo"  :type :checkbox}
                                                  {:name "rakennuksenPurkaminen" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.rakennuksenPurkaminen"  :type :checkbox}
                                                  {:name "maanrakennustyo" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.maanrakennustyo"  :type :checkbox}
                                                  {:name "rakennelmaTaiLaitos" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.rakennelmaTaiLaitos"  :type :checkbox}
                                                  {:name "elementtienAsennus" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.elementtienAsennus"  :type :checkbox}
                                                  {:name "terasRakenteet_tiilirakenteet" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.terasRakenteet_tiilirakenteet"  :type :checkbox}
                                                  {:name "kiinteistonVesiJaViemarilaitteistonRakentaminen" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.kiinteistonVesiJaViemarilaitteistonRakentaminen"  :type :checkbox}
                                                  {:name "kiinteistonilmanvaihtolaitteistonRakentaminen" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.kiinteistonilmanvaihtolaitteistonRakentaminen"  :type :checkbox}
                                                  {:name "muuMika" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.muuMika"  :type :string}]}])

(def kuntaroolikoodi-tyonjohtaja [{:name "kuntaRoolikoodi"
                                   :i18nkey "osapuoli.tyonjohtaja.kuntaRoolikoodi._group_label"
                                   :type :select
                                   :sortBy :displayname
                                   :required true
                                   :body [{:name "KVV-ty\u00F6njohtaja" :i18nkey "osapuoli.tyonjohtaja.kuntaRoolikoodi.KVV-ty\u00f6njohtaja"}
                                          {:name "IV-ty\u00F6njohtaja" :i18nkey "osapuoli.tyonjohtaja.kuntaRoolikoodi.IV-ty\u00f6njohtaja"}
                                          {:name "erityisalojen ty\u00F6njohtaja" :i18nkey "osapuoli.tyonjohtaja.kuntaRoolikoodi.erityisalojen ty\u00f6njohtaja"}
                                          {:name "vastaava ty\u00F6njohtaja" :i18nkey "osapuoli.tyonjohtaja.kuntaRoolikoodi.vastaava ty\u00f6njohtaja"}
                                          {:name "ty\u00F6njohtaja" :i18nkey "osapuoli.tyonjohtaja.kuntaRoolikoodi.ty\u00f6njohtaja"}
                                          {:name "ei tiedossa" :i18nkey "osapuoli.kuntaRoolikoodi.ei tiedossa"}]}])

(def patevyysvaatimusluokka
  {:name "patevyysvaatimusluokka" :type :select :sortBy nil :required false
   :body [{:name "AA"}
          {:name "A"}
          {:name "B"}
          {:name "C"}
          {:name "ei tiedossa"}]})

(def patevyys-tyonjohtaja [koulutusvalinta
                           {:name "koulutus" :type :string :required false  :i18nkey "muukoulutus"}
                           patevyysvaatimusluokka
                           {:name "valmistumisvuosi" :type :string :subtype :number :min-len 4 :max-len 4 :size "s" :required false}
                           {:name "kokemusvuodet" :type :string :subtype :number :min-len 1 :max-len 2 :size "s" :required false}
                           {:name "valvottavienKohteidenMaara" :i18nkey "tyonjohtaja.patevyys.valvottavienKohteidenMaara" :type :string :subtype :number :size "s" :required false}
                           ;; TODO: Miten tyonjohtajaHakemusKytkimen saa piilotettua hakijalta?
                           {:name "tyonjohtajaHakemusKytkin" :i18nkey "tyonjohtaja.patevyys.tyonjohtajaHakemusKytkin._group_label" :type :select :sortBy :displayname :required false :blacklist [:applicant]
                            :body [{:name "nimeaminen" :i18nkey "tyonjohtaja.patevyys.tyonjohtajaHakemusKytkin.nimeaminen"}
                                   {:name "hakemus" :i18nkey "tyonjohtaja.patevyys.tyonjohtajaHakemusKytkin.hakemus"}]}])

(def patevyys-tyonjohtaja-v2 [koulutusvalinta
                              {:name "koulutus" :type :string :required false  :i18nkey "muukoulutus"}
                              {:name "valmistumisvuosi" :type :string :subtype :number :min-len 4 :max-len 4 :size "s" :required false}
                              {:name "kokemusvuodet" :type :string :subtype :number :min-len 1 :max-len 2 :size "s" :required false}
                              {:name "valvottavienKohteidenMaara" :i18nkey "tyonjohtaja.patevyys.valvottavienKohteidenMaara" :type :string :subtype :number :size "s" :required false}])

;; FIXME remove + migration
(def vastuuaika-tyonjohtaja [{:name "vastuuaika"
                              :type :group
                              :hidden true
                              :body [{:name "vastuuaika-alkaa-pvm" :type :date}
                                     {:name "vastuuaika-paattyy-pvm" :type :date}]}])

(def sijaisuus-tyonjohtaja [{:name "sijaistus" :i18nkey "tyonjohtaja.sijaistus._group_label"
                             :type :group
                             :body [{:name "sijaistettavaHloEtunimi" :i18nkey "tyonjohtaja.sijaistus.sijaistettavaHloEtunimi" :type :string}
                                    {:name "sijaistettavaHloSukunimi" :i18nkey "tyonjohtaja.sijaistus.sijaistettavaHloSukunimi" :type :string}
                                    {:name "alkamisPvm" :i18nkey "tyonjohtaja.sijaistus.alkamisPvm" :type :date}
                                    {:name "paattymisPvm" :i18nkey "tyonjohtaja.sijaistus.paattymisPvm" :type :date}]}])

(def tyonjohtaja (body
                   kuntaroolikoodi-tyonjohtaja
                   vastattavat-tyotehtavat-tyonjohtaja
                   vastuuaika-tyonjohtaja
                   henkilo-valitsin
                   designer-basic
                   {:name "patevyys" :type :group :body patevyys-tyonjohtaja}
                   sijaisuus-tyonjohtaja))

(def ilmoitus-hakemus-valitsin {:name "ilmoitusHakemusValitsin" :i18nkey "tyonjohtaja.ilmoitusHakemusValitsin._group_label" :type :select :sortBy :displayname :required true :blacklist [:applicant] :layout :single-line
                                :body [{:name "ilmoitus" :i18nkey "tyonjohtaja.ilmoitusHakemusValitsin.ilmoitus"}
                                       {:name "hakemus" :i18nkey "tyonjohtaja.ilmoitusHakemusValitsin.hakemus"}]})

(def kuntaroolikoodi-tyonjohtaja-v2 [{:name "kuntaRoolikoodi"
                                      :i18nkey "osapuoli.tyonjohtaja.kuntaRoolikoodi._group_label"
                                      :type :select
                                      :emit [:filterByCode]
                                      :sortBy :displayname
                                      :required true
                                      :body [{:name "vastaava ty\u00F6njohtaja" :code :vtj :i18nkey "osapuoli.tyonjohtaja.kuntaRoolikoodi.vastaava ty\u00f6njohtaja"}
                                             {:name "KVV-ty\u00F6njohtaja" :code :kvv :i18nkey "osapuoli.tyonjohtaja.kuntaRoolikoodi.KVV-ty\u00f6njohtaja"}
                                             {:name "IV-ty\u00F6njohtaja" :code :ivt :i18nkey "osapuoli.tyonjohtaja.kuntaRoolikoodi.IV-ty\u00f6njohtaja"}
                                             {:name "erityisalojen ty\u00F6njohtaja" :code :vrt :i18nkey "osapuoli.tyonjohtaja.kuntaRoolikoodi.erityisalojen ty\u00f6njohtaja"}]}])

(def vastattavat-tyotehtavat-tyonjohtaja-v2 [{:name "vastattavatTyotehtavat"
                                              :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat._group_label"
                                              :type :group
                                              :listen [:filterByCode]
                                              :layout :vertical
                                              :body [{:name "ivLaitoksenAsennustyo" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.ivLaitoksenAsennustyo" :codes [:ivt] :type :checkbox}
                                                     {:name "ivLaitoksenKorjausJaMuutostyo" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.ivLaitoksenKorjausJaMuutostyo" :codes [:ivt] :type :checkbox}
                                                     {:name "sisapuolinenKvvTyo" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.sisapuolinenKvvTyo" :codes [:kvv] :type :checkbox}
                                                     {:name "ulkopuolinenKvvTyo" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.ulkopuolinenKvvTyo" :codes [:kvv] :type :checkbox}
                                                     {:name "rakennuksenMuutosJaKorjaustyo" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.rakennuksenMuutosJaKorjaustyo" :codes [:vtj] :type :checkbox}
                                                     {:name "uudisrakennustyoMaanrakennustoineen" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.uudisrakennustyoMaanrakennustoineen" :codes [:vtj] :type :checkbox}
                                                     {:name "uudisrakennustyoIlmanMaanrakennustoita" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.uudisrakennustyoIlmanMaanrakennustoita" :codes [:vtj] :type :checkbox}
                                                     {:name "linjasaneeraus" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.linjasaneeraus" :codes [:vtj] :type :checkbox}
                                                     {:name "maanrakennustyot" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.maanrakennustyot" :codes [:vtj] :type :checkbox}
                                                     {:name "rakennuksenPurkaminen" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.rakennuksenPurkaminen" :codes [:vtj] :type :checkbox}
                                                     {:name "muuMika" :i18nkey "osapuoli.tyonjohtaja.vastattavatTyotehtavat.muuMika" :codes [:vtj :kvv :ivt :vrt] :type :string}]}])

(def tyonjohtaja-hanketieto {:name "tyonjohtajaHanketieto" :type :group
                             :body [{:name "taysiaikainenOsaaikainen" :type :radioGroup :body [{:name "taysiaikainen"} {:name "osaaikainen"}] :default "taysiaikainen"}
                                    {:name "hankeKesto" :type :string :size "s" :unit "kuukautta" :subtype :number :min 0 :max 9999999}
                                    {:name "kaytettavaAika" :type :string :size "s" :unit "tuntiaviikko" :subtype :number :min 0 :max 168} ; 7*24 = 168h :)
                                    {:name "kayntienMaara" :type :string :size "s" :unit "kpl" :subtype :number :min 0 :max 9999999}]})

(def hanke-row
  [{:name "luvanNumero" :type :string :size "m" :label false :uicomponent :string :i18nkey "muutHankkeet.luvanNumero"}
   {:name "katuosoite" :type :string :size "m" :label false :uicomponent :string :i18nkey "muutHankkeet.katuosoite"}
   {:name "rakennustoimenpide" :type :string :size "l" :label false :uicomponent :string :i18nkey "muutHankkeet.rakennustoimenpide"}
   {:name "kokonaisala" :type :string :subtype :number :size "s" :label false :uicomponent :string :i18nkey "muutHankkeet.kokonaisala"}
   {:name "vaihe" :type :select :size "t" :label false :uicomponent :select-component :i18nkey "muutHankkeet.vaihe"
    :body [{:name "R" :i18nkey "muutHankkeet.R"}
           {:name "A" :i18nkey "muutHankkeet.A"}
           {:name "K" :i18nkey "muutHankkeet.K"}]}
   {:name "3kk" :type :string :subtype :number :size "s" :label false :uicomponent :string :i18nkey "muutHankkeet.3kk"}
   {:name "6kk" :type :string :subtype :number :size "s" :label false :uicomponent :string :i18nkey "muutHankkeet.6kk"}
   {:name "9kk" :type :string :subtype :number :size "s" :label false :uicomponent :string :i18nkey "muutHankkeet.9kk"}
   {:name "12kk" :type :string :subtype :number  :size "s" :label false :uicomponent :string :i18nkey "muutHankkeet.12kk"}
   {:name "autoupdated" :type :checkbox :hidden true :i18nkey "muutHankkeet.autoupdated" :uicomponent :checkbox :whitelist [:none]}])

(def muut-rakennushankkeet-table {:name "muutHankkeet"
                                  :type :foremanOtherApplications
                                  :uicomponent :hanke-table
                                  :repeating true
                                  :approvable true
                                  :copybutton false
                                  :listen [:hetuChanged]
                                  :body hanke-row})

(def tayta-omat-tiedot-button {:name "fillMyInfo" :type :fillMyInfoButton :whitelist [:applicant]})

(def tyonjohtajan-historia {:name "foremanHistory" :type :foremanHistory})

(def tyonjohtajan-hyvaksynta [{:name "tyonjohtajanHyvaksynta"
                               :type :group
                               :whitelist [:authority]
                               :body [{:name "tyonjohtajanHyvaksynta" :type :checkbox :i18nkey "tyonjohtaja.historia.hyvaksynta"}
                                      tyonjohtajan-historia]}])

(def tyonjohtaja-v2 (body
                      tyonjohtajan-hyvaksynta
                      ilmoitus-hakemus-valitsin
                      kuntaroolikoodi-tyonjohtaja-v2
                      patevyysvaatimusluokka
                      vastattavat-tyotehtavat-tyonjohtaja-v2
                      tyonjohtaja-hanketieto
                      tayta-omat-tiedot-button
                      designer-basic
                      muut-rakennushankkeet-table
                      {:name "patevyys" :type :group :body patevyys-tyonjohtaja-v2}
                      sijaisuus-tyonjohtaja))

(def maksaja (body
               (henkilo-yritys-select-group :yritys-body yritys-with-verkkolaskutustieto)
               {:name "laskuviite" :type :string :max-len 30 :layout :full-width}))

(def muutostapa {:name "muutostapa" :type :select :sortBy :displayname :label false :i18nkey "huoneistot.muutostapa" :emit [:muutostapaChanged]
                 :body [{:name "poisto"}
                        {:name "lis\u00e4ys" :i18nkey "huoneistot.muutostapa.lisays"}
                        {:name "muutos"}]})

(def huoneistoRow [{:name "huoneistoTyyppi" :type :select :sortBy :displayname :label false :i18nkey "huoneistot.huoneistoTyyppi" :listen [:muutostapaChanged]
                   :body [{:name "asuinhuoneisto"}
                          {:name "toimitila"}
                          {:name "ei tiedossa" :i18nkey "huoneistot.huoneistoTyyppi.eiTiedossa"}]}
                   {:name "porras" :type :string :subtype :letter :case :upper :max-len 1 :size "t" :label false :i18nkey "huoneistot.porras" :listen [:muutostapaChanged]}
                   {:name "huoneistonumero" :type :string :subtype :number :min-len 1 :max-len 3 :size "s" :required true :label false :i18nkey "huoneistot.huoneistonumero" :listen [:muutostapaChanged]}
                   {:name "jakokirjain" :type :string :subtype :letter :case :lower :max-len 1 :size "t" :label false :i18nkey "huoneistot.jakokirjain" :listen [:muutostapaChanged]}
                   {:name "huoneluku" :type :string :subtype :number :min 1 :max 99 :required true :size "t" :label false :i18nkey "huoneistot.huoneluku" :listen [:muutostapaChanged]}
                   {:name "keittionTyyppi" :type :select :sortBy :displayname :required true :label false :i18nkey "huoneistot.keittionTyyppi" :listen [:muutostapaChanged]
                    :body [{:name "keittio"}
                           {:name "keittokomero"}
                           {:name "keittotila"}
                           {:name "tupakeittio"}
                           {:name "ei tiedossa" :i18nkey "huoneistot.keittionTyyppi.eiTiedossa"}]}
                   {:name "huoneistoala" :type :string :subtype :number :size "s" :min 1 :max 9999999 :required true :label false :i18nkey "huoneistot.huoneistoala" :listen [:muutostapaChanged]}
                   {:name "WCKytkin" :type :checkbox :label false :i18nkey "huoneistot.WCKytkin" :listen [:muutostapaChanged]}
                   {:name "ammeTaiSuihkuKytkin" :type :checkbox :label false :i18nkey "huoneistot.ammeTaiSuihkuKytkin" :listen [:muutostapaChanged]}
                   {:name "saunaKytkin" :type :checkbox :label false :i18nkey "huoneistot.saunaKytkin" :listen [:muutostapaChanged]}
                   {:name "parvekeTaiTerassiKytkin" :type :checkbox :label false :i18nkey "huoneistot.parvekeTaiTerassiKytkin" :listen [:muutostapaChanged]}
                   {:name "lamminvesiKytkin" :type :checkbox :label false :i18nkey "huoneistot.lamminvesiKytkin" :listen [:muutostapaChanged]}
                   muutostapa])

(def huoneistotTable {:name "huoneistot"
                      :i18nkey "huoneistot"
                      :type :table
                      :group-help "huoneistot.groupHelpText"
                      :repeating true
                      :approvable true
                      :copybutton true
                      :body huoneistoRow})

(def yhden-asunnon-talot "011 yhden asunnon talot")
(def rivitalot "021 rivitalot")
(def vapaa-ajan-asuinrakennus "041 vapaa-ajan asuinrakennukset")
(def talousrakennus "941 talousrakennukset")
(def rakennuksen-kayttotarkoitus [{:name yhden-asunnon-talot}
                                  {:name "012 kahden asunnon talot"}
                                  {:name "013 muut erilliset talot"}
                                  {:name rivitalot}
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

(def kaytto {:name "kaytto"
             :type :group
             :body [{:name "rakentajaTyyppi" :type :select :sortBy :displayname :required true
                     :body [{:name "liiketaloudellinen"}
                            {:name "muu"}
                            {:name "ei tiedossa"}]}
                    {:name "kayttotarkoitus" :type :select :sortBy :displayname :size "l"
                     :body rakennuksen-kayttotarkoitus}]})

(def mitat {:name "mitat"
            :type :group
            :body [{:name "tilavuus" :type :string :size "s" :unit "m3" :subtype :number :min 1 :max 9999999}
                   {:name "kerrosala" :type :string :size "s" :unit "m2" :subtype :number :min 1 :max 9999999}
                   {:name "kokonaisala" :type :string :size "s" :unit "m2" :subtype :number :min 1 :max 9999999}
                   {:name "kerrosluku" :type :string :size "s" :subtype :number :min 0 :max 50}
                   {:name "kellarinpinta-ala" :type :string :size "s" :unit "m2" :subtype :number :min 1 :max 9999999}]})

(def mitat-muutos {:name "mitat"
                        :type :group
                        :whitelist [:authority]
                        :body [{:name "tilavuus" :type :string :size "s" :unit "m3" :subtype :number :min 1 :max 9999999}
                               {:name "kerrosala" :type :string :size "s" :unit "m2" :subtype :number :min 1 :max 9999999}
                               {:name "kokonaisala" :type :string :size "s" :unit "m2" :subtype :number :min 1 :max 9999999}
                               {:name "kerrosluku" :type :string :size "s" :subtype :number :min 0 :max 50}
                               {:name "kellarinpinta-ala" :type :string :size "s" :unit "m2" :subtype :number :min 1 :max 9999999}]})

(def rakenne {:name "rakenne"
              :type :group
              :body [{:name "rakentamistapa" :type :select :sortBy :displayname :required true
                      :body [{:name "elementti"}
                             {:name "paikalla"}
                             {:name "ei tiedossa"}]}
                     {:name "kantavaRakennusaine" :type :select :sortBy :displayname :required true :other-key "muuRakennusaine"
                      :body [{:name "betoni"}
                             {:name "tiili"}
                             {:name "ter\u00e4s"}
                             {:name "puu"}
                             {:name "ei tiedossa"}]}
                     {:name "muuRakennusaine" :type :string}
                     {:name "julkisivu" :type :select :sortBy :displayname :other-key "muuMateriaali"
                      :body [{:name "betoni"}
                             {:name "tiili"}
                             {:name "metallilevy"}
                             {:name "kivi"}
                             {:name "puu"}
                             {:name "lasi"}
                             {:name "ei tiedossa"}]}
                     {:name "muuMateriaali" :type :string}]})

(def lammitys {:name "lammitys"
               :type :group
               :body [{:name "lammitystapa" :type :select :sortBy :displayname
                       :body [{:name "vesikeskus"}
                              {:name "ilmakeskus"}
                              {:name "suora s\u00e4hk\u00f6"}
                              {:name "uuni"}
                              {:name "ei l\u00e4mmityst\u00e4"}
                              {:name "ei tiedossa"}]}
                      {:name "lammonlahde" :type :select :sortBy :displayname :other-key "muu-lammonlahde"
                       :body [{:name "kauko tai aluel\u00e4mp\u00f6"}
                              {:name "kevyt poltto\u00f6ljy"}
                              {:name "raskas poltto\u00f6ljy"}
                              {:name "s\u00e4hk\u00f6"}
                              {:name "kaasu"}
                              {:name "kiviihiili koksi tms"}
                              {:name "turve"}
                              {:name "maal\u00e4mp\u00f6"}
                              {:name "puu"}
                              {:name "ei tiedossa"}]}
                      {:name "muu-lammonlahde" :type :string}]})

(def verkostoliittymat {:name "verkostoliittymat" :type :group :layout :vertical
                        :body [{:name "viemariKytkin" :type :checkbox}
                               {:name "vesijohtoKytkin" :type :checkbox}
                               {:name "sahkoKytkin" :type :checkbox}
                               {:name "maakaasuKytkin" :type :checkbox}
                               {:name "kaapeliKytkin" :type :checkbox}]})
(def varusteet {:name "varusteet" :type :group :layout :vertical
                                                     :body [{:name "sahkoKytkin" :type :checkbox}
                                                            {:name "kaasuKytkin" :type :checkbox}
                                                            {:name "viemariKytkin" :type :checkbox}
                                                            {:name "vesijohtoKytkin" :type :checkbox}
                                                            {:name "hissiKytkin" :type :checkbox}
                                                            {:name "koneellinenilmastointiKytkin" :type :checkbox}
                                                            {:name "lamminvesiKytkin" :type :checkbox}
                                                            {:name "aurinkopaneeliKytkin" :type :checkbox}
                                                            {:name "saunoja" :type :string :subtype :number :min 1 :max 99 :size "s" :unit "kpl"}
                                                            {:name "vaestonsuoja" :type :string :subtype :number :min 1 :max 99999 :size "s" :unit "hengelle"}
                                                            {:name "liitettyJatevesijarjestelmaanKytkin" :type :checkbox}]})

(def luokitus {:name "luokitus"
               :type :group
               :body [{:name "energialuokka" :type :select :sortBy :displayname
                       :body [{:name "A"}
                              {:name "B"}
                              {:name "C"}
                              {:name "D"}
                              {:name "E"}
                              {:name "F"}
                              {:name "G"}]}
                      {:name "energiatehokkuusluku" :type :string :size "s" :subtype :number}
                      {:name "energiatehokkuusluvunYksikko" :type :select :sortBy :displayname
                       :body [{:name "kWh/m2"}
                              {:name "kWh/brm2/vuosi"}]}
                      {:name "paloluokka" :type :select :sortBy :displayname
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
                               {:name "P1/P2/P3"}]}]})

(def rakennuksen-tiedot-ilman-huoneistoa [kaytto
                                          mitat
                                          rakenne
                                          lammitys
                                          verkostoliittymat
                                          varusteet
                                          luokitus])

(def rakennuksen-tiedot-ilman-huoneistoa-muutos [kaytto
                                                 mitat-muutos
                                                 rakenne
                                                 lammitys
                                                 verkostoliittymat
                                                 varusteet
                                                 luokitus])

(def rakennuksen-tiedot-ilman-huoneistoa-ilman-ominaisuustietoja [kaytto
                                                                  rakenne
                                                                  mitat])

(def rakennuksen-tiedot-ilman-huoneistoa-ilman-ominaisuustietoja-muutos [kaytto
                                                                         rakenne
                                                                         mitat-muutos])

(def rakennuksen-tiedot (conj rakennuksen-tiedot-ilman-huoneistoa huoneistotTable))

(def rakennuksen-tiedot-muutos (conj rakennuksen-tiedot-ilman-huoneistoa-muutos huoneistotTable))


(def rakennelma (body
                  [{:name "kokonaisala" :type :string :size "s" :unit "m2" :subtype :number}]
                  kuvaus))
(def maisematyo (body kuvaus))

(def rakennuksen-omistajat [{:name "rakennuksenOmistajat"
                             :type :group
                             :repeating true
                             :approvable true
                             :body (body party-with-required-hetu
                                     [{:name "omistajalaji" :type :select :sortBy :displayname :other-key "muu-omistajalaji" :required true :size "l"
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
                     {:name "muutostyolaji" :type :select :sortBy :displayname :required true
                      :body
                      [{:name perustusten-korjaus}
                       {:name kayttotarkotuksen-muutos}
                       {:name muumuutostyo}]}])

(def olemassaoleva-rakennus (body
                              rakennuksen-valitsin
                              rakennuksen-omistajat
                              rakennuksen-osoite
                              rakennuksen-tiedot))

(def olemassaoleva-rakennus-muutos (body
                                     rakennuksen-valitsin
                                     rakennuksen-omistajat
                                     rakennuksen-osoite
                                     rakennuksen-tiedot-muutos))

(def olemassaoleva-rakennus-ei-huoneistoja (body
                                             rakennuksen-valitsin
                                             rakennuksen-omistajat
                                             rakennuksen-osoite
                                             rakennuksen-tiedot-ilman-huoneistoa))

(def olemassaoleva-rakennus-ei-huoneistoja-muutos (body
                                                    rakennuksen-valitsin
                                                    rakennuksen-omistajat
                                                    rakennuksen-osoite
                                                    rakennuksen-tiedot-ilman-huoneistoa-muutos))

(def olemassaoleva-rakennus-ei-huoneistoja-ei-ominaisuus-tietoja
  (body rakennuksen-valitsin
        rakennuksen-omistajat
        rakennuksen-osoite
        rakennuksen-tiedot-ilman-huoneistoa-ilman-ominaisuustietoja))

(def olemassaoleva-rakennus-ei-huoneistoja-ei-ominaisuus-tietoja-muutos
  (body rakennuksen-valitsin
        rakennuksen-omistajat
        rakennuksen-osoite
        rakennuksen-tiedot-ilman-huoneistoa-ilman-ominaisuustietoja-muutos))

(def rakennuksen-muuttaminen-ei-huoneistoja (body
                                              muutostyonlaji
                                              olemassaoleva-rakennus-ei-huoneistoja))

(def rakennuksen-muuttaminen-ei-huoneistoja-muutos (body
                                                     muutostyonlaji
                                                     olemassaoleva-rakennus-ei-huoneistoja-muutos))

(def rakennuksen-muuttaminen-ei-huoneistoja-ei-ominaisuus-tietoja (body
                                                                    muutostyonlaji
                                                                    olemassaoleva-rakennus-ei-huoneistoja-ei-ominaisuus-tietoja))

(def rakennuksen-muuttaminen-ei-huoneistoja-ei-ominaisuus-tietoja-muutos (body
                                                                    muutostyonlaji
                                                                    olemassaoleva-rakennus-ei-huoneistoja-ei-ominaisuus-tietoja-muutos))

(def rakennuksen-muuttaminen (body
                               muutostyonlaji
                               olemassaoleva-rakennus))

(def rakennuksen-muuttaminen-muutos (body
                               muutostyonlaji
                               olemassaoleva-rakennus-muutos))

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
                                                              {:name "kayttotarkoitusKoodi" :type :select :sortBy :displayname
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
             {:name "poistumanSyy" :type :select :sortBy :displayname
              :body [{:name "purettu uudisrakentamisen vuoksi"}
                     {:name "purettu muusta syyst\u00e4"}
                     {:name "tuhoutunut"}
                     {:name "r\u00e4nsistymisen vuoksi hyl\u00e4tty"}
                     {:name "poistaminen"}]}
             {:name "poistumanAjankohta" :type :date}
             olemassaoleva-rakennus-ei-huoneistoja-ei-ominaisuus-tietoja))

(def rakennuspaikka [{:name "kiinteisto"
                      :type :group
                      :body [{:name "maaraalaTunnus" :type :string :subtype :maaraala-tunnus :size "s"}
                             {:name "tilanNimi" :type :string :readonly true}
                             {:name "rekisterointipvm" :type :string :readonly true}
                             {:name "maapintaala" :type :string :readonly true :unit "hehtaaria"}
                             {:name "vesipintaala" :type :string :readonly true :unit "hehtaaria"}
                             {:name "rantaKytkin" :type :checkbox}]}
                     {:name "hallintaperuste" :type :select :sortBy :displayname :required true
                      :body [{:name "oma"}
                             {:name "vuokra"}
                             {:name "ei tiedossa"}]}
                     {:name "kaavanaste" :type :select :sortBy :displayname :hidden true
                      :body [{:name "asema"}
                             {:name "ranta"}
                             {:name "rakennus"}
                             {:name "yleis"}
                             {:name "ei kaavaa"}
                             {:name "ei tiedossa"}]}
                     {:name "kaavatilanne" :type :select :sortBy :displayname
                      :body [{:name "maakuntakaava"}
                             {:name "oikeusvaikutteinen yleiskaava"}
                             {:name "oikeusvaikutukseton yleiskaava"}
                             {:name "asemakaava"}
                             {:name "ranta-asemakaava"}
                             {:name "ei kaavaa"}]}])


(defn- approvable-top-level-groups [v]
  (map #(if (= (:type %) :group) (assoc % :approvable true) %) v))

;;
;; schemas
;;

(defschemas
  1
  [{:info {:name "hankkeen-kuvaus-minimum"
           :approvable true
           :order 1}
    :body [kuvaus]}

   {:info {:name "hankkeen-kuvaus"
           :approvable true
           :order 1}
    :body [kuvaus
           {:name "poikkeamat" :type :text :max-len 4000 :layout :full-width}]}

   {:info {:name "uusiRakennus" :approvable true}
    :body (body rakennuksen-omistajat (approvable-top-level-groups rakennuksen-tiedot))}

   {:info {:name "uusi-rakennus-ei-huoneistoa" :i18name "uusiRakennus" :approvable true}
    :body (body rakennuksen-omistajat (approvable-top-level-groups rakennuksen-tiedot-ilman-huoneistoa))}

   {:info {:name "rakennuksen-muuttaminen-ei-huoneistoja" :i18name "rakennuksen-muuttaminen" :approvable true}
    :body (approvable-top-level-groups rakennuksen-muuttaminen-ei-huoneistoja-muutos)}

   {:info {:name "rakennuksen-muuttaminen-ei-huoneistoja-ei-ominaisuuksia" :i18name "rakennuksen-muuttaminen" :approvable true}
     :body (approvable-top-level-groups rakennuksen-muuttaminen-ei-huoneistoja-ei-ominaisuus-tietoja-muutos)}

   {:info {:name "rakennuksen-muuttaminen" :approvable true}
     :body (approvable-top-level-groups rakennuksen-muuttaminen-muutos)}

    {:info {:name "rakennuksen-laajentaminen" :approvable true}
     :body (approvable-top-level-groups rakennuksen-laajentaminen)}

    {:info {:name "purkaminen" :i18name "purku" :approvable true}
     :body (approvable-top-level-groups purku)}

    {:info {:name "kaupunkikuvatoimenpide" :approvable true}
     :body (approvable-top-level-groups rakennelma)}

    {:info {:name "maisematyo" :approvable true}
     :body (approvable-top-level-groups maisematyo)}
    {:info {:name "kiinteistotoimitus" :approvable true}
     :body (approvable-top-level-groups (body kuvaus))}

    {:info {:name "maankayton-muutos" :approvable true}
     :body (approvable-top-level-groups (body kuvaus))}

    {:info {:name "hakija"
            :i18name "osapuoli"
            :order 3
            :removable true
            :repeating true
            :approvable true
            :type :party
            :subtype :hakija
            :section-help "party.section.help"
            :after-update 'lupapalvelu.application-meta-fields/applicant-index-update
            }
     :body party}

    {:info {:name "hakija-ya"
            :i18name "osapuoli"
            :order 3
            :removable false
            :repeating false
            :approvable true
            :type :party
            :subtype :hakija
            :section-help "party.section.help"
            :after-update 'lupapalvelu.application-meta-fields/applicant-index-update}
     :body (schema-body-without-element-by-name ya-party turvakielto)}

    {:info {:name "paasuunnittelija"
            :i18name "osapuoli"
            :order 4
            :removable false
            :approvable true
            :type :party}
     :body paasuunnittelija}

    {:info {:name "suunnittelija"
            :i18name "osapuoli"
            :repeating true
            :order 5
            :removable true
            :approvable true
            :type :party}
     :body suunnittelija}

    {:info {:name "tyonjohtaja"
            :i18name "osapuoli"
            :order 5
            :removable true
            :repeating true
            :approvable true
            :type :party}
     :body tyonjohtaja}

    {:info {:name "tyonjohtaja-v2"
            :i18name "osapuoli"
            :order 5
            :removable false
            :repeating false
            :approvable true
            :type :party}
     :body tyonjohtaja-v2}

    {:info {:name "maksaja"
            :i18name "osapuoli"
            :repeating true
            :order 6
            :removable true
            :approvable true
            :type :party}
     :body maksaja}

    {:info {:name "rakennuspaikka"
            :approvable true
            :order 2
            :type :location}
     :body (schema-body-without-element-by-name rakennuspaikka "rantaKytkin")}

    {:info {:name "kiinteisto"
            :approvable true
            :order 2
            :type :location}
     :body (schema-body-without-element-by-name rakennuspaikka "rantaKytkin" "hallintaperuste" "kaavanaste" "kaavatilanne")}

    {:info {:name "paatoksen-toimitus-rakval"
            :removable false
            :approvable true
            :order 10}
     :body (body
             [(update-in henkilotiedot-minimal [:body] (partial remove #(= turvakielto (:name %))))]
             simple-osoite
             [{:name "yritys" :type :group
               :body [{:name "yritysnimi" :type :string}]}]
             tayta-omat-tiedot-button)}

    {:info {:name "aloitusoikeus" :removable false :approvable true}
     :body (body kuvaus)}

    {:info {:name "lisatiedot"
            :order 100}
     :body [{:name "suoramarkkinointikielto" ;THIS IS DEPRECATED!
             :type :checkbox
             :layout :full-width}]}])
