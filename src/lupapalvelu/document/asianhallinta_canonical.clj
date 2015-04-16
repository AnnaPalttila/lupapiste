(ns lupapalvelu.document.asianhallinta_canonical
  (require [lupapalvelu.document.canonical-common :refer :all]
           [lupapalvelu.document.tools :as tools]
           [lupapalvelu.xml.disk-writer :as writer]
           [clojure.string :as s]
           [sade.core :refer :all]
           [sade.util :as util]
           [lupapalvelu.i18n :as i18n]))


;; UusiAsia, functions prefixed with ua-

(def ^:private ua-root-element {:UusiAsia nil})

(defn- ua-get-asian-tyyppi-string [application]
  ; KasiteltavaHakemus, TODO later: Tiedoksianto
  "KasiteltavaHakemus")

(defn- ua-get-yhteystiedot [data]
  (util/strip-nils
    {:Jakeluosoite (get-in data [:osoite :katu])
     :Postinumero (get-in data [:osoite :postinumero])
     :Postitoimipaikka (get-in data [:osoite :postitoimipaikannimi])
     :Maa nil ; TODO
     :Email (get-in data [:yhteystiedot :email])
     :Puhelinnumero (get-in data [:yhteystiedot :puhelin])}))

(defn- ua-get-yhteyshenkilo [data]
  (util/strip-nils
    {:Etunimi (get-in data [:yhteyshenkilo :henkilotiedot :etunimi])
     :Sukunimi (get-in data [:yhteyshenkilo :henkilotiedot :sukunimi])
     :Yhteystiedot (ua-get-yhteystiedot (:yhteyshenkilo data))}))

(defn- ua-get-henkilo [data]
  (util/strip-nils
    {:Etunimi (get-in data [:henkilo :henkilotiedot :etunimi])
     :Sukunimi (get-in data [:henkilo :henkilotiedot :sukunimi])
     :Yhteystiedot (ua-get-yhteystiedot (:henkilo data))
     :Henkilotunnus (get-in data [:henkilo :henkilotiedot :hetu])
     :VainSahkoinenAsiointi nil ; TODO tarviiko tata
     :Turvakielto (get-in data [:henkilo :henkilotiedot :turvakieltoKytkin])}))

(defn- ua-get-yritys [data]
  {:Nimi (get-in data [:yritys :yritysnimi])
   :Ytunnus (get-in data [:yritys :liikeJaYhteisoTunnus])
   :Yhteystiedot (ua-get-yhteystiedot (:yritys data))
   :Yhteyshenkilo (ua-get-yhteyshenkilo (:yritys data))})

(defn- ua-get-hakijat [documents]
  (when (seq documents)
    {:Hakija (map
               (fn [doc]
                 (let [hakija-data (:data doc)
                       sel (:_selected hakija-data)]
                   (if (= sel "yritys")
                     {:Yritys (ua-get-yritys hakija-data)}
                     {:Henkilo (ua-get-henkilo hakija-data)})))
               documents)}))

(defn- ua-get-maksaja [{data :data}]
  (let [sel (:_selected data)
        maksaja-map (util/strip-empty-maps
                      (util/strip-nils
                        {:Laskuviite (:laskuviite data)
                         :Verkkolaskutustieto (when (= "yritys" sel)
                                                {:OVT-tunnus (get-in data [:yritys :verkkolaskutustieto :ovtTunnus])
                                                 :Verkkolaskutunnus (get-in data [:yritys :verkkolaskutustieto :verkkolaskuTunnus])
                                                 :Operaattoritunnus (get-in data [:yritys :verkkolaskutustieto :valittajaTunnus])})}))]

    (if (= sel "yritys")
      (util/strip-empty-maps (assoc-in maksaja-map [:Yritys] (ua-get-yritys data)))
      (util/strip-empty-maps (assoc-in maksaja-map [:Henkilo] (ua-get-henkilo data))))))

(defn- ua-get-metatiedot [attachment]
  (let [op-name (get-in attachment [:op :name])
        type-group (get-in attachment [:type :type-group])
        type-id (get-in attachment [:type :type-id])]
    (remove nil? [(when type-group {:Avain "type-group" :Arvo type-group})
                  (when type-id    {:Avain "type-id"    :Arvo type-id})
                  (when op-name    {:Avain "operation" :Arvo op-name})])))

(defn- ua-get-toimenpide [operation lang]
  (util/strip-nils
    {:ToimenpideTunnus (:name operation)
     :ToimenpideTeksti (i18n/localize lang "operations" (:name operation))}))

(defn- ua-get-toimenpiteet [{:keys [operations]} lang]
  (when (seq operations)
    {:Toimenpide (map #(-> % (ua-get-toimenpide lang)) operations)}))

(def- viitelupa-mapping
  {"kuntalupatunnus" "Taustaj\u00E4rjestelm\u00E4"
   "lupapistetunnus" "Lupapiste"})

(defn- ua-get-viitelupa [linkPermit]
  (util/strip-nils
    {:MuuTunnus {:Tunnus (:id linkPermit)
                 :Sovellus (viitelupa-mapping (:type linkPermit))}}))

(defn- ua-get-viiteluvat [{:keys [linkPermitData]}]
  (when (seq linkPermitData)
    {:Viitelupa (map ua-get-viitelupa linkPermitData)}))

(defn- ua-get-sijaintipiste [{:keys [location]}]
  {:Sijaintipiste (str (:x location) " " (:y location))})

(defn- ua-get-liite [attachment link]
  "Return attachment in canonical format, with provided link as LinkkiLiitteeseen"
  (util/strip-nils
    {:Kuvaus (get-in attachment [:type :type-id])
     :Tyyppi (get-in attachment [:latestVersion :contentType])
     :LinkkiLiitteeseen link
     :Luotu (util/to-xml-date (:modified attachment))
     :Metatiedot {:Metatieto (ua-get-metatiedot attachment)}}))

;; Public

(defn get-attachments-as-canonical [attachments begin-of-link & [target]]
  (not-empty
    (for [attachment attachments
          :when (and (:latestVersion attachment)
                  (not= "statement" (-> attachment :target :type))
                  (not= "verdict" (-> attachment :target :type))
                  (or (nil? target) (= target (:target attachment))))
          :let [file-id (get-in attachment [:latestVersion :fileId])
                attachment-file-name (writer/get-file-name-on-server file-id (get-in attachment [:latestVersion :filename]))
                link (str begin-of-link attachment-file-name)]]
      (ua-get-liite attachment link))))

(defn get-submitted-application-pdf [{:keys [id submitted]} begin-of-link]
  {:Kuvaus "Vireille tullut hakemus"
   :Tyyppi "application/pdf"
   :LinkkiLiitteeseen (str begin-of-link (writer/get-submitted-filename id))
   :Luotu (util/to-xml-date submitted)
   :Metatiedot {:Metatieto [{:Avain "type-group" :Arvo "hakemus"}
                            {:Avain "type-id"    :Arvo "hakemus_vireilletullessa"}]}})

(defn get-current-application-pdf [{:keys [id]} begin-of-link]
  {:Kuvaus "J\u00e4rjestelm\u00e4\u00e4n siirrett\u00e4ess\u00e4"
   :Tyyppi "application/pdf"
   :LinkkiLiitteeseen (str begin-of-link (writer/get-current-filename id))
   :Luotu (util/to-xml-date (sade.core/now))
   :Metatiedot {:Metatieto [{:Avain "type-group" :Arvo "hakemus"}
                            {:Avain "type-id"    :Arvo "hakemus_jarjestelmaan_siirrettaessa"}]}})

;; TaydennysAsiaan, prefix: ta-

(def ^:private ta-root-element {:TaydennysAsiaan nil})

;; AsianPaatos, prefix: ap-


;; AsianTunnusVastaus, prefix: atr-


(defn application-to-asianhallinta-canonical [application lang]
  "Return canonical, does not contain attachments"
  (let [documents (tools/unwrapped (documents-by-type-without-blanks application))]
    (-> (assoc-in ua-root-element [:UusiAsia :Tyyppi] (ua-get-asian-tyyppi-string application))
      (assoc-in [:UusiAsia :Kuvaus] (:title application))
      (assoc-in [:UusiAsia :Kuntanumero] (:municipality application))
      (assoc-in [:UusiAsia :Hakijat] (ua-get-hakijat (:hakija documents)))
      (assoc-in [:UusiAsia :Maksaja] (ua-get-maksaja (first (:maksaja documents))))
      (assoc-in [:UusiAsia :HakemusTunnus] (:id application))
      (assoc-in [:UusiAsia :VireilletuloPvm] (util/to-xml-date (:submitted application)))
      (assoc-in [:UusiAsia :Asiointikieli] lang)
      (assoc-in [:UusiAsia :Toimenpiteet] (ua-get-toimenpiteet application lang))
      (assoc-in [:UusiAsia :Sijainti] (ua-get-sijaintipiste application))
      (assoc-in [:UusiAsia :Kiinteistotunnus] (util/to-human-readable-property-id (:propertyId application)))
      (assoc-in [:UusiAsia :Viiteluvat] (ua-get-viiteluvat application)))))

(defn application-to-asianhallinta-taydennys-asiaan-canonical [application]
  "Return TaydennysAsiaan canonical"
  (-> (assoc-in ta-root-element [:TaydennysAsiaan :HakemusTunnus] (:id application))))
