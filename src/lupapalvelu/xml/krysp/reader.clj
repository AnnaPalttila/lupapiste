(ns lupapalvelu.xml.krysp.reader
  "Read the Krysp from municipality Web Feature Service"
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error]]
            [clojure.string :as s]
            [clojure.set :refer [rename-keys]]
            [clojure.walk :refer [postwalk prewalk]]
            [clj-time.format :as timeformat]
            [net.cgrand.enlive-html :as enlive]
            [ring.util.codec :as codec]
            [sade.xml :refer :all]
            [sade.http :as http]
            [sade.util :as util]
            [sade.common-reader :as cr]
            [sade.strings :as ss]
            [sade.coordinate :as coordinate]
            [sade.core :refer [now def- fail]]
            [sade.property :as p]
            [sade.validators :as v]
            [lupapalvelu.document.schemas :as schema]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.xml.krysp.verdict :as verdict]))

;; Object types (URL encoded)
(def building-type    "typeName=rakval%3AValmisRakennus")
(def rakval-case-type "typeName=rakval%3ARakennusvalvontaAsia")
(def poik-case-type   "typeName=ppst%3APoikkeamisasia,ppst%3ASuunnittelutarveasia")
(def ya-type          "typeName=yak%3AYleisetAlueet")
(def yl-case-type     "typeName=ymy%3AYmparistolupa")
(def mal-case-type    "typeName=ymm%3AMaaAineslupaAsia")
(def vvvl-case-type   "typeName=ymv%3AVapautus")

;;(def kt-case-type-prefix  "typeName=kiito%3A")

;; Object types as enlive selector
(def case-elem-selector #{[:RakennusvalvontaAsia]
                          [:Poikkeamisasia]
                          [:Suunnittelutarveasia]
                          [:Sijoituslupa]
                          [:Kayttolupa]
                          [:Liikennejarjestelylupa]
                          [:Tyolupa]
                          [:Ilmoitukset]
                          [:Ymparistolupa]
                          [:MaaAineslupaAsia]
                          [:Vapautus]})

(def outlier-elem-selector #{[:Lohkominen]
                             [:Rasitetoimitus]
                             [:YleisenAlueenLohkominen]
                             [:KiinteistolajinMuutos]
                             [:YhtAlueenOsuuksienSiirto]
                             [:KiinteistojenYhdistaminen]
                             [:Halkominen]
                             [:KiinteistonMaaritys]
                             [:Tilusvaihto]})

;; Only those types supported by Facta are included.
(def kt-types (let [elems (map (comp (partial str "kiito:") name)
                               [:KiinteistolajinMuutos
                                :KiinteistojenYhdistaminen
                                :Lohkominen
                                :YleisenAlueenLohkominen
                                :Rasitetoimitus])]
                (str "typeName=" (ss/join "," elems))))


(defn- get-tunnus-path
  [permit-type search-type]
  (let [prefix (permit/get-metadata permit-type :wfs-krysp-url-asia-prefix)
        tunnus-location (case search-type
                          :application-id  "yht:LupaTunnus/yht:muuTunnustieto/yht:MuuTunnus/yht:tunnus"
                          :kuntalupatunnus "yht:LupaTunnus/yht:kuntalupatunnus")]
    (str prefix tunnus-location)))

(def- rakennuksen-kiinteistotunnus "rakval:rakennustieto/rakval:Rakennus/rakval:rakennuksenTiedot/rakval:rakennustunnus/rakval:kiinttun")

(defn property-equals
  "Returns URL-encoded search parameter suitable for 'filter'"
  [property value]
  (codec/url-encode (str "<PropertyIsEqualTo><PropertyName>" (escape-xml property) "</PropertyName><Literal>" (escape-xml value) "</Literal></PropertyIsEqualTo>")))

(defn- post-body-for-ya-application [id id-path]
  {:body (str "<wfs:GetFeature service=\"WFS\"
        version=\"1.1.0\"
        outputFormat=\"GML2\"
        xmlns:yak=\"http://www.paikkatietopalvelu.fi/gml/yleisenalueenkaytonlupahakemus\"
        xmlns:wfs=\"http://www.opengis.net/wfs\"
        xmlns:ogc=\"http://www.opengis.net/ogc\"
        xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">
        <wfs:Query typeName=\"yak:Sijoituslupa,yak:Kayttolupa,yak:Liikennejarjestelylupa,yak:Tyolupa\">
          <ogc:Filter>
            <ogc:PropertyIsEqualTo>
                       <ogc:PropertyName>" id-path "</ogc:PropertyName>
                       <ogc:Literal>" id "</ogc:Literal>
            </ogc:PropertyIsEqualTo>
          </ogc:Filter>
         </wfs:Query>
       </wfs:GetFeature>")})

(defn wfs-krysp-url [server object-type filter]
  (let [server (if (ss/contains? server "?")
                 (if (ss/ends-with server "&")
                   server
                   (str server "&"))
                 (str server "?"))]
    (str server "request=GetFeature&" object-type "&filter=" filter)))

(defn wfs-krysp-url-with-service [server object-type filter]
  (str (wfs-krysp-url server object-type filter) "&service=WFS"))

(defn building-xml
  "Returns clojure.xml map or an empty map if the data could not be downloaded."
  ([server credentials property-id]
   (building-xml server credentials property-id false))
  ([server credentials property-id raw?]
   (let [url (wfs-krysp-url server building-type (property-equals rakennuksen-kiinteistotunnus property-id))]
     (trace "Get building: " url)
     (or (cr/get-xml url credentials raw?) {}))))

(defn- application-xml [type-name id-path server credentials id raw?]
  (let [url (wfs-krysp-url-with-service server type-name (property-equals id-path id))]
    (trace "Get application: " url)
    (cr/get-xml url credentials raw?)))

(defn rakval-application-xml [server credentials id search-type raw?]
  (application-xml rakval-case-type (get-tunnus-path permit/R search-type)    server credentials id raw?))

(defn poik-application-xml   [server credentials id search-type raw?]
  (application-xml poik-case-type   (get-tunnus-path permit/P search-type)    server credentials id raw?))

(defn yl-application-xml     [server credentials id search-type raw?]
  (application-xml yl-case-type     (get-tunnus-path permit/YL search-type)   server credentials id raw?))

(defn mal-application-xml    [server credentials id search-type raw?]
  (application-xml mal-case-type    (get-tunnus-path permit/MAL search-type)  server credentials id raw?))

(defn vvvl-application-xml   [server credentials id search-type raw?]
  (application-xml vvvl-case-type   (get-tunnus-path permit/VVVL search-type) server credentials id raw?))

(defn ya-application-xml     [server credentials id search-type raw?]
  (let [options (post-body-for-ya-application id (get-tunnus-path permit/YA search-type))]
    (trace "Get application: " server " with post body: " options )
    (cr/get-xml-with-post server options credentials raw?)))

(defn kt-application-xml   [server credentials id search-type raw?]
  (let [path "kiito:toimitushakemustieto/kiito:Toimitushakemus/kiito:hakemustunnustieto/kiito:Hakemustunnus/yht:tunnus"]
    (application-xml kt-types path server credentials id raw?)))

(permit/register-function permit/R    :xml-from-krysp rakval-application-xml)
(permit/register-function permit/P    :xml-from-krysp poik-application-xml)
(permit/register-function permit/YA   :xml-from-krysp ya-application-xml)
(permit/register-function permit/YL   :xml-from-krysp yl-application-xml)
(permit/register-function permit/MAL  :xml-from-krysp mal-application-xml)
(permit/register-function permit/VVVL :xml-from-krysp vvvl-application-xml)
(permit/register-function permit/KT   :xml-from-krysp kt-application-xml)


(defn- pysyva-rakennustunnus
  "Returns national building id or nil if the input was not valid"
  [^String s]
  (let [building-id (ss/trim (str s))]
    (when (v/rakennustunnus? building-id)
      building-id)))

(defn- ->building-ids [id-container xml-no-ns]
  (defn ->list "a as list or nil. a -> [a], [b] -> [b]" [a] (when a (-> a list flatten)))
  (let [national-id    (pysyva-rakennustunnus (get-text xml-no-ns id-container :valtakunnallinenNumero))
        local-short-id (-> (get-text xml-no-ns id-container :rakennusnro) ss/trim (#(when-not (ss/blank? %) %)))
        local-id       (-> (get-text xml-no-ns id-container :kunnanSisainenPysyvaRakennusnumero) ss/trim (#(when-not (ss/blank? %) %)))
        edn            (-> xml-no-ns (select [id-container]) first xml->edn id-container)]
    {:propertyId   (get-text xml-no-ns id-container :kiinttun)
     :buildingId   (first (remove ss/blank? [national-id local-short-id]))
     :nationalId   national-id
     :localId      local-id
     :localShortId local-short-id
     :index        (get-text xml-no-ns id-container :jarjestysnumero)
     :usage        (or (get-text xml-no-ns :kayttotarkoitus) "")
     :area         (get-text xml-no-ns :kokonaisala)
     :created      (->> (get-text xml-no-ns :alkuHetki) cr/parse-datetime (cr/unparse-datetime :year))
     :tags         (map (fn [{{:keys [tunnus sovellus]} :MuuTunnus}] {:tag tunnus :id sovellus}) (->list (:muuTunnustieto edn)))
     :description (:rakennuksenSelite edn)}))

(defn ->buildings-summary [xml]
  (let [xml-no-ns (cr/strip-xml-namespaces xml)]
    (distinct
      (concat
        (map (partial ->building-ids :rakennustunnus) (select xml-no-ns [:Rakennus]))
        (map (partial ->building-ids :tunnus) (select xml-no-ns [:Rakennelma]))))))

;;
;; Mappings from KRYSP to Lupapiste domain
;;

(def ...notfound... nil)
(def ...notimplemented... nil)

(defn- str-or-nil [& v]
  (when-not (some nil? v) (reduce str v)))

(defn- get-updated-if [current to-add]
   (if to-add
    (str current to-add)
    current))

(defn get-osoite [osoite]
  (-> (get-text osoite :osoitenimi :teksti)
    (get-updated-if (str-or-nil " " (get-text osoite :osoitenumero)))
    (get-updated-if (str-or-nil "\u2013" (get-text osoite :osoitenumero2)));SFS4175 stardardin mukainen valiviiva
    (get-updated-if (str-or-nil " " (get-text osoite :jakokirjain)))
    (get-updated-if (str-or-nil "\u2013" (get-text osoite :jakokirjain2)))
    (get-updated-if (str-or-nil " " (get-text osoite :porras)))
    (get-updated-if (str-or-nil " " (get-text osoite :huoneisto)))))

(defn- ->henkilo [xml-without-ns]
  (let [henkilo (select1 xml-without-ns [:henkilo])]
    {:_selected "henkilo"
     :henkilo   {:henkilotiedot {:etunimi  (get-text henkilo :nimi :etunimi)
                                 :sukunimi (get-text henkilo :nimi :sukunimi)
                                 :hetu     (get-text henkilo :henkilotunnus)
                                 :turvakieltoKytkin (cr/to-boolean (get-text xml-without-ns :turvakieltoKytkin))}
                 :yhteystiedot  {:email     (get-text henkilo :sahkopostiosoite)
                                 :puhelin   (get-text henkilo :puhelin)}
                 :osoite        {:katu         (get-osoite (select1 henkilo :osoite))
                                 :postinumero  (get-text henkilo :osoite :postinumero)
                                 :postitoimipaikannimi  (get-text henkilo :osoite :postitoimipaikannimi)}}
     :omistajalaji     (get-text xml-without-ns :omistajalaji :omistajalaji)
     :muu-omistajalaji (get-text xml-without-ns :omistajalaji :muu)}))

(defn- ->yritys [xml-without-ns]
  (let [yritys (select1 xml-without-ns [:yritys])]
    {:_selected "yritys"
     :yritys {:yritysnimi                             (get-text yritys :nimi)
              :liikeJaYhteisoTunnus                   (get-text yritys :liikeJaYhteisotunnus)
              :osoite {:katu                          (get-osoite (select1 yritys :postiosoite))
                       :postinumero                   (get-text yritys :postiosoite :postinumero)
                       :postitoimipaikannimi          (get-text yritys :postiosoite :postitoimipaikannimi)}
              :yhteyshenkilo (-> (->henkilo xml-without-ns) :henkilo (dissoc :osoite))}
     :omistajalaji     (get-text xml-without-ns :omistajalaji :omistajalaji)
     :muu-omistajalaji (get-text xml-without-ns :omistajalaji :muu)}))

(defn- ->rakennuksen-omistaja-legacy-version [omistaja]
  {:_selected "yritys"
   :yritys {:liikeJaYhteisoTunnus                     (get-text omistaja :tunnus)
            :osoite {:katu                            (get-text omistaja :osoitenimi :teksti)
                     :postinumero                     (get-text omistaja :postinumero)
                     :postitoimipaikannimi            (get-text omistaja :postitoimipaikannimi)}
            :yhteyshenkilo {:henkilotiedot {:etunimi  (get-text omistaja :henkilonnimi :etunimi)      ;; does-not-exist in test
                                            :sukunimi (get-text omistaja :henkilonnimi :sukunimi)     ;; does-not-exist in test
                            :yhteystiedot {:email     ...notfound...
                                           :puhelin   ...notfound...}}}
            :yritysnimi                               (get-text omistaja :nimi)}})

(defn- ->rakennuksen-omistaja [omistaja]
  (cond
    (seq (select omistaja [:yritys])) (->yritys omistaja)
    (seq (select omistaja [:henkilo])) (->henkilo omistaja)
    :default (->rakennuksen-omistaja-legacy-version omistaja)))

(def cleanup (comp util/strip-empty-maps util/strip-nils))

(def polished  (comp cr/index-maps cleanup cr/convert-booleans))

(def empty-building-ids {:valtakunnallinenNumero ""
                         :rakennusnro ""})

(defn ->rakennuksen-tiedot
  ([xml building-id]
    (let [stripped  (cr/strip-xml-namespaces xml)
          rakennus  (or
                      (select1 stripped [:rakennustieto :> (under [:valtakunnallinenNumero (has-text building-id)])])
                      (select1 stripped [:rakennustieto :> (under [:rakennusnro (has-text building-id)])]))]
      (->rakennuksen-tiedot rakennus)))
  ([rakennus]
    (when rakennus
      (util/deep-merge
        (->
          {:body schema/rakennuksen-muuttaminen}
          (tools/create-unwrapped-data tools/default-values)
          ; Dissoc values that are not read
          (dissoc :buildingId :muutostyolaji :perusparannuskytkin)
          (util/dissoc-in [:huoneistot :0 :muutostapa]))
        (polished
          (util/assoc-when
            {:muutostyolaji                          ...notimplemented...
             :valtakunnallinenNumero                 (pysyva-rakennustunnus (get-text rakennus :rakennustunnus :valtakunnallinenNumero))
             ;; TODO: Add support for kunnanSisainenPysyvaRakennusnumero (rakval krysp 2.1.6 +)
;             :kunnanSisainenPysyvaRakennusnumero (get-text rakennus :rakennustunnus :kunnanSisainenPysyvaRakennusnumero)
             :rakennusnro                            (ss/trim (get-text rakennus :rakennustunnus :rakennusnro))
             :manuaalinen_rakennusnro                ""
             :jarjestysnumero                        (get-text rakennus :rakennustunnus :jarjestysnumero)
             :kiinttun                               (get-text rakennus :rakennustunnus :kiinttun)
             :verkostoliittymat                      (cr/all-of rakennus [:verkostoliittymat])

             :osoite {:kunta                         (get-text rakennus :osoite :kunta)
                      :lahiosoite                    (get-text rakennus :osoite :osoitenimi :teksti)
                      :osoitenumero                  (get-text rakennus :osoite :osoitenumero)
                      :osoitenumero2                 (get-text rakennus :osoite :osoitenumero2)
                      :jakokirjain                   (get-text rakennus :osoite :jakokirjain)
                      :jakokirjain2                  (get-text rakennus :osoite :jakokirjain2)
                      :porras                        (get-text rakennus :osoite :porras)
                      :huoneisto                     (get-text rakennus :osoite :huoneisto)
                      :postinumero                   (get-text rakennus :osoite :postinumero)
                      :postitoimipaikannimi          (get-text rakennus :osoite :postitoimipaikannimi)}
             :kaytto {:kayttotarkoitus               (get-text rakennus :kayttotarkoitus)
                      :rakentajaTyyppi               (get-text rakennus :rakentajaTyyppi)}
             :luokitus {:energialuokka               (get-text rakennus :energialuokka)
                        :paloluokka                  (get-text rakennus :paloluokka)}
             :mitat {:kellarinpinta-ala              (get-text rakennus :kellarinpinta-ala)
                     :kerrosala                      (get-text rakennus :kerrosala)
                     :rakennusoikeudellinenKerrosala (get-text rakennus :rakennusoikeudellinenKerrosala)
                     :kerrosluku                     (get-text rakennus :kerrosluku)
                     :kokonaisala                    (get-text rakennus :kokonaisala)
                     :tilavuus                       (get-text rakennus :tilavuus)}
             :rakenne {:julkisivu                    (get-text rakennus :julkisivumateriaali)
                       :kantavaRakennusaine          (get-text rakennus :rakennusaine)
                       :rakentamistapa               (get-text rakennus :rakentamistapa)}
             :lammitys {:lammitystapa                (get-text rakennus :lammitystapa)
                        :lammonlahde                 (get-text rakennus :polttoaine)}
             :varusteet                              (-> (cr/all-of rakennus :varusteet)
                                                         (dissoc :uima-altaita) ; key :uima-altaita has been removed from lupapiste
                                                         (merge {:liitettyJatevesijarjestelmaanKytkin (get-text rakennus :liitettyJatevesijarjestelmaanKytkin)}))}

            :rakennuksenOmistajat (->> (select rakennus [:omistaja]) (map ->rakennuksen-omistaja))
            :huoneistot (->> (select rakennus [:valmisHuoneisto])
                          (map (fn [huoneisto]
                                {:huoneistonumero (get-text huoneisto :huoneistonumero)
                                 :jakokirjain     (get-text huoneisto :jakokirjain)
                                 :porras          (get-text huoneisto :porras)
                                 :huoneistoTyyppi (get-text huoneisto :huoneistonTyyppi)
                                 :huoneistoala    (ss/replace (get-text huoneisto :huoneistoala) "." ",")
                                 :huoneluku       (get-text huoneisto :huoneluku)
                                 :keittionTyyppi  (get-text huoneisto :keittionTyyppi)
                                 :WCKytkin                (get-text huoneisto :WCKytkin)
                                 :ammeTaiSuihkuKytkin     (get-text huoneisto :ammeTaiSuihkuKytkin)
                                 :lamminvesiKytkin        (get-text huoneisto :lamminvesiKytkin)
                                 :parvekeTaiTerassiKytkin (get-text huoneisto :parvekeTaiTerassiKytkin)
                                 :saunaKytkin             (get-text huoneisto :saunaKytkin)}))
                          (sort-by (juxt :porras :huoneistonumero :jakokirjain)))))))))

(defn ->buildings [xml]
  (map ->rakennuksen-tiedot (-> xml cr/strip-xml-namespaces (select [:Rakennus]))))

(defn- extract-vaadittuErityissuunnitelma-elements [lupamaaraykset]
  (let [vaadittuErityissuunnitelma-array (->>
                                           (or
                                             (->> lupamaaraykset :vaadittuErityissuunnitelmatieto (map :vaadittuErityissuunnitelma) seq)  ;; Yhteiset Krysp 2.1.6 ->
                                             (:vaadittuErityissuunnitelma lupamaaraykset))                                                ;; Yhteiset Krysp -> 2.1.5
                                           (map ss/trim)
                                           (remove ss/blank?))]

    ;; resolving Tekla way of giving vaadittuErityissuunnitelmas: one "vaadittuErityissuunnitelma" with line breaks is divided into multiple "vaadittuErityissuunnitelma"s
    (if (and
          (= 1 (count vaadittuErityissuunnitelma-array))
          (-> vaadittuErityissuunnitelma-array first (.indexOf "\n") (>= 0)))
      (-> vaadittuErityissuunnitelma-array first (ss/split #"\n") ((partial remove ss/blank?)))
      vaadittuErityissuunnitelma-array)))

(defn- extract-maarays-elements [lupamaaraykset]
  (let [maaraykset (or
                     (->> lupamaaraykset :maaraystieto (map :Maarays) seq)  ;; Yhteiset Krysp 2.1.6 ->
                     (:maarays lupamaaraykset))]                            ;; Yhteiset Krysp -> 2.1.5
    (->> (cr/convert-keys-to-timestamps maaraykset [:maaraysaika :maaraysPvm :toteutusHetki])
      (map #(rename-keys % {:maaraysPvm :maaraysaika}))
      (remove nil?))))

(defn- ->lupamaaraukset [paatos-xml-without-ns]
  (-> (cr/all-of paatos-xml-without-ns :lupamaaraykset)
    (cleanup)

    ;; KRYSP yhteiset 2.1.5+
    (util/ensure-sequential :vaadittuErityissuunnitelma)
    (util/ensure-sequential :vaadittuErityissuunnitelmatieto)
    (#(let [vaaditut-es (extract-vaadittuErityissuunnitelma-elements %)]
        (if (seq vaaditut-es) (assoc % :vaaditutErityissuunnitelmat vaaditut-es) %)))
    (dissoc :vaadittuErityissuunnitelma :vaadittuErityissuunnitelmatieto)

    (util/ensure-sequential :vaaditutKatselmukset)
    (#(let [kats (map :Katselmus (:vaaditutKatselmukset %))]
        (if (seq kats)
          (assoc % :vaaditutKatselmukset kats)
          (dissoc % :vaaditutKatselmukset))))

    ; KRYSP yhteiset 2.1.1+
    (util/ensure-sequential :vaadittuTyonjohtajatieto)
    (#(let [tyonjohtajat (map
                           (comp (fn [tj] (util/some-key tj :tyonjohtajaLaji :tyonjohtajaRooliKoodi)) :VaadittuTyonjohtaja)  ;; "tyonjohtajaRooliKoodi" in KRYSP Yhteiset 2.1.6->
                           (:vaadittuTyonjohtajatieto %))]
        (if (seq tyonjohtajat)
          (-> %
            (assoc :vaadittuTyonjohtajatieto tyonjohtajat)
            ; KRYSP yhteiset 2.1.0 and below have vaaditutTyonjohtajat key that contains the same data in a single string.
            ; Convert the new format to the old.
            (assoc :vaaditutTyonjohtajat (s/join ", " tyonjohtajat)))
          (dissoc % :vaadittuTyonjohtajatieto))))

    (util/ensure-sequential :maarays)
    (util/ensure-sequential :maaraystieto)
    (#(if-let [maaraykset (seq (extract-maarays-elements %))]
        (assoc % :maaraykset maaraykset)
        %))
    (dissoc :maarays :maaraystieto)

    (cr/convert-keys-to-ints [:autopaikkojaEnintaan
                              :autopaikkojaVahintaan
                              :autopaikkojaRakennettava
                              :autopaikkojaRakennettu
                              :autopaikkojaKiinteistolla
                              :autopaikkojaUlkopuolella])))

(defn- ->lupamaaraukset-text [paatos-xml-without-ns]
  (let [lupaehdot (select paatos-xml-without-ns :lupaehdotJaMaaraykset)]
    (when (not-empty lupaehdot)
      (-> lupaehdot
        (cleanup)
        ((fn [maar] (map #(get-text % :lupaehdotJaMaaraykset) maar)))
        (util/ensure-sequential :lupaehdotJaMaaraykset)))))

(defn- get-pvm-dates [paatos v]
  (into {} (map #(let [xml-kw (keyword (str (name %) "Pvm"))]
                   [% (cr/to-timestamp (get-text paatos xml-kw))]) v)))

(defn- ->liite [{:keys [metatietotieto] :as liite}]
  (-> liite
    (assoc  :metadata (into {} (map
                                 (fn [{meta :metatieto}]
                                   [(keyword (:metatietoNimi meta)) (:metatietoArvo meta)])
                                 (if (sequential? metatietotieto) metatietotieto [metatietotieto]))))
    (dissoc :metatietotieto)
    (cr/convert-keys-to-timestamps [:muokkausHetki])))

(defn- ->paatospoytakirja [paatos-xml-without-ns]
  (-> (cr/all-of paatos-xml-without-ns :poytakirja)
    (cr/convert-keys-to-ints [:pykala])
    (cr/convert-keys-to-timestamps [:paatospvm])
    (#(assoc % :status (verdict/verdict-id (:paatoskoodi %))))
    (#(update-in % [:liite] ->liite))))

(defn- poytakirja-with-paatos-data [poytakirjat]
  (some #(when (and (:paatoskoodi %) (:paatoksentekija %) (:paatospvm %)) %) poytakirjat))

(defn- valid-paatospvm? [paatos-pvm]
  (> (util/get-timestamp-ago :day 1) paatos-pvm))

(defn- valid-antopvm? [anto-pvm]
  (or (not anto-pvm) (> (now) anto-pvm)))

(defn- standard-verdicts-validator [xml {validate-verdict-given-date :validate-verdict-given-date}]
  (let [paatos-xml-without-ns (select (cr/strip-xml-namespaces xml) [:paatostieto :Paatos])
        poytakirjat (map ->paatospoytakirja (select paatos-xml-without-ns [:poytakirja]))
        poytakirja  (poytakirja-with-paatos-data poytakirjat)
        paivamaarat (map #(get-pvm-dates % [:aloitettava :lainvoimainen :voimassaHetki :raukeamis :anto :viimeinenValitus :julkipano]) paatos-xml-without-ns)]
    (cond
      (not (seq poytakirjat))                               (fail :info.no-verdicts-found-from-backend)
      (not (seq poytakirja))                                (fail :info.paatos-details-missing)
      (not (valid-paatospvm? (:paatospvm poytakirja)))      (fail :info.paatos-future-date)
      (and validate-verdict-given-date
        (not-any? #(valid-antopvm? (:anto %)) paivamaarat)) (fail :info.paatos-future-date))))

(defn- ->standard-verdicts [xml-without-ns]
  (map (fn [paatos-xml-without-ns]
         (let [poytakirjat      (map ->paatospoytakirja (select paatos-xml-without-ns [:poytakirja]))
               poytakirja       (poytakirja-with-paatos-data poytakirjat)
               paivamaarat      (get-pvm-dates paatos-xml-without-ns [:aloitettava :lainvoimainen :voimassaHetki :raukeamis :anto :viimeinenValitus :julkipano])]
           (when (and poytakirja (valid-paatospvm? (:paatospvm poytakirja)))
             {:lupamaaraykset (->lupamaaraukset paatos-xml-without-ns)
              :paivamaarat    paivamaarat
              :poytakirjat    (seq poytakirjat)})))
    (select xml-without-ns [:paatostieto :Paatos])))


;; TJ/Suunnittelija verdict

(def- tj-suunnittelija-verdict-statuses-to-loc-keys-mapping
  {"hyv\u00e4ksytty" "hyvaksytty"
   "hyl\u00e4tty" "hylatty"
   "hakemusvaiheessa" "hakemusvaiheessa"
   "ilmoitus hyv\u00e4ksytty" "ilmoitus-hyvaksytty"})

(def- tj-suunnittelija-verdict-statuses
  (-> tj-suunnittelija-verdict-statuses-to-loc-keys-mapping keys set))

(defn- ->paatos-osapuoli [path-key osapuoli-xml-without-ns]
  (-> (cr/all-of osapuoli-xml-without-ns path-key)
    (cr/convert-keys-to-timestamps [:paatosPvm])))

(defn- valid-sijaistustieto? [osapuoli sijaistus]
  (when osapuoli
    (or
     (empty? sijaistus) ; sijaistus only used with foreman roles
     (and ; sijaistettava must be empty in both, KRSYP and document
       (ss/blank? (:sijaistettavaHlo osapuoli))
       (and
         (ss/blank? (:sijaistettavaHloEtunimi sijaistus))
         (ss/blank? (:sijaistettavaHloSukunimi sijaistus))))
     (and ; .. or dates and input values of KRYSP xml must match document values
       (= (:alkamisPvm osapuoli) (util/to-xml-date-from-string (:alkamisPvm sijaistus)))
       (= (:paattymisPvm osapuoli) (util/to-xml-date-from-string (:paattymisPvm sijaistus)))
       (=
         (ss/trim (:sijaistettavaHlo osapuoli))
         (str ; original string build in canonical-common 'get-sijaistustieto'
           (ss/trim (:sijaistettavaHloEtunimi sijaistus))
           " "
           (ss/trim (:sijaistettavaHloSukunimi sijaistus))))))))

(defn- party-with-paatos-data [osapuolet sijaistus]
  (some
    #(when (and
             (:paatosPvm %)
             (tj-suunnittelija-verdict-statuses (:paatostyyppi %))
             (valid-sijaistustieto? % sijaistus))
       %)
    osapuolet))

(def- osapuoli-path-key-mapping
  {"tyonjohtaja"   {:path [:tyonjohtajatieto :Tyonjohtaja]
                    :key :tyonjohtajaRooliKoodi}
   "suunnittelija" {:path [:suunnittelijatieto :Suunnittelija]
                    :key :suunnittelijaRoolikoodi}})

(defn- get-tj-suunnittelija-osapuolet
  "Returns parties which match with given kuntaRoolikoodi and yhteystiedot, and have paatosPvm"
  [xml-without-ns osapuoli-path osapuoli-key kuntaRoolikoodi-key kuntaRoolikoodi yhteystiedot]
  (->> (select xml-without-ns osapuoli-path)
    (map (partial ->paatos-osapuoli osapuoli-key))
    (filter #(and
               (= kuntaRoolikoodi (get % kuntaRoolikoodi-key))
               (:paatosPvm %)
               (= (:email yhteystiedot) (get-in % [:henkilo :sahkopostiosoite]))))))

(defn tj-suunnittelija-verdicts-validator [{{:keys [yhteystiedot sijaistus]} :data} xml osapuoli-type kuntaRoolikoodi]
  {:pre [xml (#{"tyonjohtaja" "suunnittelija"} osapuoli-type) kuntaRoolikoodi]}
  (let [{osapuoli-path :path kuntaRoolikoodi-key :key} (osapuoli-path-key-mapping osapuoli-type)
        osapuoli-key (last osapuoli-path)
        xml-without-ns (cr/strip-xml-namespaces xml)
        osapuolet (get-tj-suunnittelija-osapuolet xml-without-ns osapuoli-path osapuoli-key kuntaRoolikoodi-key kuntaRoolikoodi yhteystiedot)
        osapuoli (party-with-paatos-data osapuolet sijaistus)
        paatospvm  (:paatosPvm osapuoli)
        timestamp-1-day-ago (util/get-timestamp-ago :day 1)]
    (cond
      (not (seq osapuolet))                  (fail :info.no-verdicts-found-from-backend)
      (not (seq osapuoli))                   (fail :info.tj-suunnittelija-paatos-details-missing)
      (< timestamp-1-day-ago paatospvm)      (fail :info.paatos-future-date))))

(defn ->tj-suunnittelija-verdicts [{{:keys [yhteystiedot sijaistus]} :data} osapuoli-type kuntaRoolikoodi xml-without-ns]
  (let [{osapuoli-path :path kuntaRoolikoodi-key :key} (osapuoli-path-key-mapping osapuoli-type)
        osapuoli-key (last osapuoli-path)]
    (map (fn [osapuolet-xml-without-ns]
           (let [osapuolet (get-tj-suunnittelija-osapuolet xml-without-ns osapuoli-path osapuoli-key kuntaRoolikoodi-key kuntaRoolikoodi yhteystiedot)
                 osapuoli (party-with-paatos-data osapuolet sijaistus)]
           (when (and osapuoli (> (now) (:paatosPvm osapuoli)))
             {:poytakirjat
              [{:status (get tj-suunnittelija-verdict-statuses-to-loc-keys-mapping (:paatostyyppi osapuoli))
                :paatospvm (:paatosPvm osapuoli)
                :liite (:liite osapuoli)
                }]
              })))
  (select xml-without-ns [:osapuolettieto :Osapuolet]))))

(defn- application-state [xml-without-ns]
  (->> (select xml-without-ns [:Kasittelytieto])
    (map (fn [kasittelytieto] (-> (cr/all-of kasittelytieto) (cr/convert-keys-to-timestamps [:muutosHetki]))))
    (filter :hakemuksenTila) ;; this because hakemuksenTila is optional in Krysp, and can be nil
    (sort-by :muutosHetki)
    last
    :hakemuksenTila
    ss/lower-case))

(def backend-preverdict-state
  #{"" "luonnos" "hakemus" "valmistelussa" "vastaanotettu" "tarkastettu, t\u00e4ydennyspyynt\u00f6"})

(defn- simple-verdicts-validator [xml organization & verdict-date-path]
  (let [verdict-date-path (or verdict-date-path [:paatostieto :Paatos :paatosdokumentinPvm])
        xml-without-ns (cr/strip-xml-namespaces xml)
        app-state      (application-state xml-without-ns)
        paivamaarat    (filter number? (map (comp cr/to-timestamp get-text) (select xml-without-ns verdict-date-path)))
        max-date       (when (seq paivamaarat) (apply max paivamaarat))
        pre-verdict?   (contains? backend-preverdict-state app-state)]
    (cond
      (nil? xml)         (fail :info.no-verdicts-found-from-backend)
      pre-verdict?       (fail :info.application-backend-preverdict-state)
      (nil? max-date)    (fail :info.paatos-date-missing)
      (< (now) max-date) (fail :info.paatos-future-date))))

(defn- ->simple-verdicts [xml-without-ns]
  ;; using the newest app state in the message
  (let [app-state (application-state xml-without-ns)]
    (when-not (contains? backend-preverdict-state app-state)
      (map (fn [paatos-xml-without-ns]
             (let [paatosdokumentinPvm-timestamp (cr/to-timestamp (get-text paatos-xml-without-ns :paatosdokumentinPvm))]
               (when (and paatosdokumentinPvm-timestamp (> (now) paatosdokumentinPvm-timestamp))
                 {:lupamaaraykset {:takuuaikaPaivat (get-text paatos-xml-without-ns :takuuaikaPaivat)
                                   :muutMaaraykset (->lupamaaraukset-text paatos-xml-without-ns)}
                  :paivamaarat    {:paatosdokumentinPvm paatosdokumentinPvm-timestamp}
                  :poytakirjat    (when-let [liitetiedot (seq (select paatos-xml-without-ns [:liitetieto]))]
                                    (map ->liite
                                         (map #(-> %
                                                 (cr/as-is :Liite)
                                                 (rename-keys {:Liite :liite}))
                                              liitetiedot)))})))
        (select xml-without-ns [:paatostieto :Paatos])))))

(defn- outlier-verdicts-validator [xml organization]
  (simple-verdicts-validator xml organization :paatostieto :Paatos :pvmtieto :Pvm :pvm))

(defn ->outlier-verdicts
  "For some reason kiinteistotoimitus (at least) defines its own
  verdict schema, which is similar to but not the same as the common
  schema"
  [xml-no-ns]
  (let [app-state (application-state xml-no-ns)]
    (when-not (contains? backend-preverdict-state app-state)
      (map (fn [verdict]
             (let [timestamp (cr/to-timestamp (get-text verdict [:pvmtieto :Pvm :pvm]))]
               (when (and timestamp (> (now) timestamp))
                 (let [poytakirjat (for [elem (select verdict [:poytakirjatieto])
                                         :let [pk (-> elem cr/as-is :poytakirjatieto :Poytakirja)
                                               fields (select-keys pk [:paatoksentekija :pykala])
                                               paatos (:paatos pk)
                                               liitteet (map #(-> % :Liite ->liite (dissoc :metadata))
                                                             (flatten [(:liitetieto pk)]))]]
                                     (assoc fields
                                            :paatoskoodi paatos
                                            :status (verdict/verdict-id paatos)
                                            :liite liitteet))]
                   {:paivamaarat    {:paatosdokumentinPvm timestamp}
                    :poytakirjat poytakirjat}))))
           (select xml-no-ns [:paatostieto :Paatos])))))


(permit/register-function permit/R :verdict-krysp-reader ->standard-verdicts)
(permit/register-function permit/P :verdict-krysp-reader ->standard-verdicts)
(permit/register-function permit/YA :verdict-krysp-reader ->simple-verdicts)
(permit/register-function permit/YL :verdict-krysp-reader ->simple-verdicts)
(permit/register-function permit/MAL :verdict-krysp-reader ->simple-verdicts)
(permit/register-function permit/VVVL :verdict-krysp-reader ->simple-verdicts)
(permit/register-function permit/KT :verdict-krysp-reader ->outlier-verdicts)

(permit/register-function permit/R :tj-suunnittelija-verdict-krysp-reader ->tj-suunnittelija-verdicts)

(permit/register-function permit/R :verdict-krysp-validator standard-verdicts-validator)
(permit/register-function permit/P :verdict-krysp-validator standard-verdicts-validator)
(permit/register-function permit/YA :verdict-krysp-validator simple-verdicts-validator)
(permit/register-function permit/YL :verdict-krysp-validator simple-verdicts-validator)
(permit/register-function permit/MAL :verdict-krysp-validator simple-verdicts-validator)
(permit/register-function permit/VVVL :verdict-krysp-validator simple-verdicts-validator)
(permit/register-function permit/KT :verdict-krysp-validator outlier-verdicts-validator)

(defn- ->lp-tunnus [asia]
  (or (get-text asia [:luvanTunnisteTiedot :LupaTunnus :muuTunnustieto :tunnus])
      (get-text asia [:luvanTunnistetiedot :LupaTunnus :muuTunnustieto :tunnus])))

(defn- ->kuntalupatunnus [asia]
  (or (get-text asia [:luvanTunnisteTiedot :LupaTunnus :kuntalupatunnus])
      (get-text asia [:luvanTunnistetiedot :LupaTunnus :kuntalupatunnus])))


;; Reads the verdicts
;; Arguments:
;; xml: KRYSP with ns.
;; fun: verdict-krysp-reader for permit.
(defmulti ->verdicts (fn [_ fun] fun))

(defmethod ->verdicts :default
  [xml ->function]
  (map
    (fn [asia]
      (let [verdict-model {:kuntalupatunnus (->kuntalupatunnus asia)}
            verdicts      (->> asia
                           (->function)
                           (cleanup)
                           (filter seq))]
        (util/assoc-when verdict-model :paatokset verdicts)))
    (enlive/select (cr/strip-xml-namespaces xml) case-elem-selector)))

;; Outliers (KT) do not have kuntalupatunnus
(defmethod ->verdicts ->outlier-verdicts
  [xml fun]
  (for [elem (enlive/select (cr/strip-xml-namespaces xml)
                            outlier-elem-selector)]
    {:kuntalupatunnus "-"
     :paatokset (->> elem fun cleanup (filter seq))}))


(defn- buildings-summary-for-application [xml]
  (let [summary (->buildings-summary xml)]
    (when (seq summary)
      {:buildings summary})))

(permit/register-function permit/R :verdict-extras-krysp-reader buildings-summary-for-application)


;; Coordinates

(def- to-projection "EPSG:3067")
(def- allowed-projection-prefix "EPSG:")

(defn- ->source-projection [xml path]
  (let [source-projection-attr (select1-attribute-value xml path :srsName)                          ;; e.g. "urn:x-ogc:def:crs:EPSG:3879"
        source-projection-point-dimension (-> (select1-attribute-value xml path :srsDimension) (util/->int false))]
    (when (and source-projection-attr (= 2 source-projection-point-dimension))
     (let [projection-name-index    (.lastIndexOf source-projection-attr allowed-projection-prefix) ;; find index of "EPSG:"
           source-projection        (when (> projection-name-index -1)
                                      (subs source-projection-attr projection-name-index))          ;; rip "EPSG:3879"
           source-projection-number (subs source-projection (count allowed-projection-prefix))]
       (if (util/->int source-projection-number false)              ;; make sure the stuff after "EPSG:" parses as an Integer
         source-projection
         (throw (Exception. (str "No coordinate source projection could be parsed from string '" source-projection-attr "'"))))))))

(defn- resolve-coordinates [point-xml-with-ns point-str kuntalupatunnus]
  (try
    (when-let [source-projection (->source-projection point-xml-with-ns [:Point])]
      (let [coords (ss/split point-str #" ")]
        (coordinate/convert source-projection to-projection 3 coords)))
    (catch Exception e (error e "Coordinate conversion failed for kuntalupatunnus " kuntalupatunnus))))


(defn- extract-osoitenimi [osoitenimi-elem lang]
  (let [osoitenimi-elem (or (select1 osoitenimi-elem [(enlive/attr= :xml:lang lang)])
                            (select1 osoitenimi-elem [(enlive/attr= :xml:lang "fi")]))]
    (cr/all-of osoitenimi-elem)))

(defn- build-huoneisto [huoneisto jakokirjain jakokirjain2]
  (when huoneisto
    (str huoneisto
         (cond
           (and jakokirjain jakokirjain2) (str jakokirjain "-" jakokirjain2)
           :else jakokirjain))))

(defn- build-osoitenumero [osoitenumero osoitenumero2]
  (cond
    (and osoitenumero osoitenumero2) (str osoitenumero "-" osoitenumero2)
    :else osoitenumero))

(defn- build-address [osoite-elem lang]
  (let [osoitenimi        (extract-osoitenimi (select osoite-elem [:osoitenimi :teksti]) lang)
        osoite            (cr/all-of osoite-elem)
        osoite-components [osoitenimi
                           (apply build-osoitenumero (util/select-values osoite [:osoitenumero :osoitenumero2]))
                           (:porras osoite)
                           (apply build-huoneisto (util/select-values osoite [:huoneisto :jakokirjain :jakokirjain2]))]]
    (clojure.string/join " " (remove nil? osoite-components))))

;;
;; Information parsed from verdict xml message for application creation
;;
(defn get-app-info-from-message [xml kuntalupatunnus]
  (let [xml-no-ns (cr/strip-xml-namespaces xml)
        kuntakoodi (-> (select1 xml-no-ns [:toimituksenTiedot :kuntakoodi]) cr/all-of)
        asiat (enlive/select xml-no-ns case-elem-selector)
        ;; Take first asia with given kuntalupatunnus. There should be only one. If there are many throw error.
        asiat-with-kuntalupatunnus (filter #(when (= kuntalupatunnus (->kuntalupatunnus %)) %) asiat)]
    (when (pos? (count asiat-with-kuntalupatunnus))
      ;; There should be only one RakennusvalvontaAsia element in the message, even though Krysp makes multiple elements possible.
      ;; Log an error if there were many. Use the first one anyway.
      (when (> (count asiat-with-kuntalupatunnus) 1)
        (error "Creating application from previous permit. More than one RakennusvalvontaAsia element were received in the xml message with kuntalupatunnus " kuntalupatunnus "."))

      (let [asia (first asiat-with-kuntalupatunnus)
            asioimiskieli (cr/all-of asia [:lisatiedot :Lisatiedot :asioimiskieli])
            asioimiskieli-code (case asioimiskieli
                                 "suomi"  "fi"
                                 "ruotsi" "sv"
                                 "fi")
            asianTiedot (cr/all-of asia [:asianTiedot :Asiantiedot])

            ;;
            ;; _Kvintus 5.11.2014_: Rakennuspaikka osoitteen ja sijainnin oikea lahde.
            ;;

            ;; Rakennuspaikka
            Rakennuspaikka (cr/all-of asia [:rakennuspaikkatieto :Rakennuspaikka])

            osoite-xml     (select asia [:rakennuspaikkatieto :Rakennuspaikka :osoite])
            osoite-Rakennuspaikka (build-address osoite-xml asioimiskieli-code)

            kiinteistotunnus (-> Rakennuspaikka :rakennuspaikanKiinteistotieto :RakennuspaikanKiinteisto :kiinteistotieto :Kiinteisto :kiinteistotunnus)
            municipality (or (p/municipality-id-by-property-id kiinteistotunnus) kuntakoodi)
            coord-array-Rakennuspaikka (resolve-coordinates
                                         (select1 asia [:rakennuspaikkatieto :Rakennuspaikka :sijaintitieto :Sijainti :piste])
                                         (-> Rakennuspaikka :sijaintitieto :Sijainti :piste :Point :pos)
                                         kuntalupatunnus)

            osapuolet (map cr/all-of (select asia [:osapuolettieto :Osapuolet :osapuolitieto :Osapuoli]))
            hakijat (filter #(= "hakija" (:VRKrooliKoodi %)) osapuolet)]

        (-> (merge
              {:id                          (->lp-tunnus asia)
               :kuntalupatunnus             (->kuntalupatunnus asia)
               :municipality                municipality
               :rakennusvalvontaasianKuvaus (:rakennusvalvontaasianKuvaus asianTiedot)
               :vahainenPoikkeaminen        (:vahainenPoikkeaminen asianTiedot)
               :hakijat                     hakijat}

              (when (and (seq coord-array-Rakennuspaikka) (not-any? ss/blank? [osoite-Rakennuspaikka kiinteistotunnus]))
                {:rakennuspaikka {:x          (first coord-array-Rakennuspaikka)
                                  :y          (second coord-array-Rakennuspaikka)
                                  :address    osoite-Rakennuspaikka
                                  :propertyId kiinteistotunnus}}))
            cr/convert-booleans
            cleanup)))))
