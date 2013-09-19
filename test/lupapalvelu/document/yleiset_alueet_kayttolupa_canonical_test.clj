(ns lupapalvelu.document.yleiset-alueet-kayttolupa-canonical-test
  (:require [lupapalvelu.document.yleiset-alueet-canonical-test-common :refer :all]
            [lupapalvelu.factlet :refer :all]
            [midje.sweet :refer :all]
            [lupapalvelu.document.canonical-common :refer :all]
            [lupapalvelu.document.yleiset-alueet-canonical :refer [application-to-canonical]]
            [sade.util :refer [contains-value?]]))


(def operation {:id "52380c6894a74fc25bb4ba46",
                :created 1379404904514,
                :name "ya-kayttolupa-tyomaasuojat-ja-muut-rakennelmat"})

(def tyoaika-kayttolupa (assoc-in tyoaika [:schema-info :op] operation))

(def documents [hakija
                tyoaika-kayttolupa
                maksaja
                hankkeen-kuvaus])

;(def allowedAttachmentTypes [["yleiset-alueet"
;                              ["aiemmin-hankittu-sijoituspaatos"
;                               "tilapainen-liikennejarjestelysuunnitelma"
;                               "tyyppiratkaisu"
;                               "tieto-kaivupaikkaan-liittyvista-johtotiedoista"
;                               "liitoslausunto"
;                               "asemapiirros"
;                               "rakennuspiirros"
;                               "suunnitelmakartta"]]
;                             ["muut" ["muu"]]])

(def kayttolupa-application {:schema-version 1,
                             :id "LP-753-2013-00001",
                             :created 1379404904514,
                             :opened 1379404981309,
                             :modified 1379405054747,
                             :submitted 1379405092649,
                             :permitType "YA",
                             :organization "753-YA",
                             :infoRequest false,
                             :authority sonja,
                             :state "submitted",
                             :title "Latokuja 1",
                             :address "Latokuja 1",
                             :location location,
                             :attachments [],
                             :operations [operation],
                             :propertyId 75341600550007,
                             :documents documents,
;                             :allowedAttachmentTypes allowedAttachmentTypes,   ;; TODO: tama pois?
                             :municipality municipality,
                             :statements statements})


(def get-maksaja #'lupapalvelu.document.yleiset-alueet-canonical/get-maksaja)

(facts* "Kayttolupa canonical model is correct"
  (let [canonical (application-to-canonical kayttolupa-application "fi")
        YleisetAlueet (:YleisetAlueet canonical) => truthy
        yleinenAlueAsiatieto (:yleinenAlueAsiatieto YleisetAlueet) => truthy
        Kayttolupa (:Kayttolupa yleinenAlueAsiatieto) => truthy

        Kasittelytieto (-> Kayttolupa :kasittelytietotieto :Kasittelytieto) => truthy
        Kasittelytieto-kasittelija-nimi (-> Kasittelytieto :kasittelija :henkilotieto :Henkilo :nimi) => truthy

        Kayttolupa-kayttotarkoitus (:kayttotarkoitus Kayttolupa) => truthy

        Sijainti-osoite (-> Kayttolupa :sijaintitieto :Sijainti :osoite) => truthy
        Sijainti-yksilointitieto (-> Sijainti-osoite :yksilointitieto) => truthy
        Sijainti-alkuHetki (-> Sijainti-osoite :alkuHetki) => truthy
        Sijainti-osoitenimi (-> Sijainti-osoite :osoitenimi :teksti) => truthy
        Sijainti-piste (-> Kayttolupa :sijaintitieto :Sijainti :piste :Point :pos) => truthy

        osapuolet-vec (-> Kayttolupa :osapuolitieto) => truthy
        vastuuhenkilot-vec (-> Kayttolupa :vastuuhenkilotieto) => truthy

        ;; maksajan henkilotieto-osa
        rooliKoodi-maksajan-vastuuhenkilo "maksajan vastuuhenkil\u00f6"
        maksaja-Vastuuhenkilo (:Vastuuhenkilo (first (filter #(= (-> % :Vastuuhenkilo :rooliKoodi) rooliKoodi-maksajan-vastuuhenkilo) vastuuhenkilot-vec)))
        maksaja-Vastuuhenkilo-osoite (-> maksaja-Vastuuhenkilo :osoitetieto :osoite) => truthy
        ;; maksajan yritystieto-osa
        Maksaja (-> Kayttolupa :maksajatieto :Maksaja) => truthy
        maksaja-Yritys (-> Maksaja :yritystieto :Yritys) => truthy
        maksaja-Yritys-postiosoite (-> maksaja-Yritys :postiosoite) => truthy

        ;; Testataan muunnosfunktiota myos yksityisella ("henkilo"-tyyppisella) maksajalla
        maksaja-yksityinen (get-maksaja
                             (assoc-in (:data maksaja) [:_selected :value] "henkilo"))
        maksaja-yksityinen-Henkilo (-> maksaja-yksityinen :henkilotieto :Henkilo) => truthy
        maksaja-yksityinen-nimi (:nimi maksaja-yksityinen-Henkilo) => truthy
        maksaja-yksityinen-osoite (:osoite maksaja-yksityinen-Henkilo) => truthy

        alkuPvm (-> Kayttolupa :alkuPvm) => truthy
        loppuPvm (-> Kayttolupa :loppuPvm) => truthy

        lupaAsianKuvaus (:lupaAsianKuvaus Kayttolupa) => truthy
        Sijoituslupaviite (-> Kayttolupa :sijoituslupaviitetieto :Sijoituslupaviite) => truthy

        rooliKoodi-Hakija "hakija"
        hakija-Osapuoli (:Osapuoli (first (filter #(= (-> % :Osapuoli :rooliKoodi) rooliKoodi-Hakija) osapuolet-vec)))
        hakija-Henkilo (-> hakija-Osapuoli :henkilotieto :Henkilo) => truthy  ;; kyseessa yrityksen vastuuhenkilo
        hakija-Yritys (-> hakija-Osapuoli :yritystieto :Yritys) => truthy
        hakija-henkilo-nimi (:nimi hakija-Henkilo) => truthy
        hakija-yritys-Postiosoite (-> hakija-Yritys :postiosoitetieto :Postiosoite) => truthy]

;    (println "\n canonical:")
;    (clojure.pprint/pprint canonical)
;    (println "\n")

    (fact "contains nil" (contains-value? canonical nil?) => falsey)

    (fact "Kasittelytieto-muutosHetki" (:muutosHetki Kasittelytieto) => (to-xml-datetime (:modified kayttolupa-application)))
    (fact "Kasittelytieto-hakemuksenTila" (:hakemuksenTila Kasittelytieto) => "vireill\u00e4")
    (fact "Kasittelytieto-asiatunnus" (:asiatunnus Kasittelytieto) => (:id kayttolupa-application))
    (fact "Kasittelytieto-paivaysPvm" (:paivaysPvm Kasittelytieto) => (to-xml-date (:opened kayttolupa-application)))
    (fact "Kasittelytieto-kasittelija-etunimi" (:etunimi Kasittelytieto-kasittelija-nimi) => (:firstName sonja))
    (fact "Kasittelytieto-kasittelija-sukunimi" (:sukunimi Kasittelytieto-kasittelija-nimi) => (:lastName sonja))

    (fact "Kayttolupa-kayttotarkoitus" Kayttolupa-kayttotarkoitus => ((keyword (:name operation)) ya-operation-type-to-usage-description))

    ;; Sijainti
    (fact "Sijainti-yksilointitieto" Sijainti-yksilointitieto => (:id kayttolupa-application))
;    (fact "Sijainti-alkuHetki" Sijainti-alkuHetki => <now??>)              ;; TODO: Mita tahan?
    (fact "Sijainti-osoitenimi" Sijainti-osoitenimi => (:address kayttolupa-application))
    (fact "Sijainti-piste-xy" Sijainti-piste => (str (-> kayttolupa-application :location :x) " " (-> kayttolupa-application :location :y)))

    ;; Maksajan tiedot
    (fact "maksaja-laskuviite" (:laskuviite Maksaja) => (:value _laskuviite))
    (fact "maksaja-rooliKoodi" (:rooliKoodi maksaja-Vastuuhenkilo) => rooliKoodi-maksajan-vastuuhenkilo)
    (fact "maksaja-henkilo-etunimi" (:etunimi maksaja-Vastuuhenkilo) => (-> nimi :etunimi :value))
    (fact "maksaja-henkilo-sukunimi" (:sukunimi maksaja-Vastuuhenkilo) => (-> nimi :sukunimi :value))
    (fact "maksaja-henkilo-sahkopostiosoite" (:sahkopostiosoite maksaja-Vastuuhenkilo) => (-> yhteystiedot :email :value))
    (fact "maksaja-henkilo-puhelinnumero" (:puhelinnumero maksaja-Vastuuhenkilo) => (-> yhteystiedot :puhelin :value))
    (fact "maksaja-henkilo-osoite-osoitenimi"
      (-> maksaja-Vastuuhenkilo-osoite :osoitenimi :teksti) => (-> osoite :katu :value))
    (fact "maksaja-henkilo-osoite-postinumero"
      (:postinumero maksaja-Vastuuhenkilo-osoite) => (-> osoite :postinumero :value))
    (fact "maksaja-henkilo-osoite-postitoimipaikannimi"
      (:postitoimipaikannimi maksaja-Vastuuhenkilo-osoite) => (-> osoite :postitoimipaikannimi :value))
    (fact "maksaja-yritys-nimi" (:nimi maksaja-Yritys) => (-> yritys-nimi-ja-tunnus :yritysnimi :value))
    (fact "maksaja-yritys-liikeJaYhteisotunnus" (:liikeJaYhteisotunnus maksaja-Yritys) => (-> yritys-nimi-ja-tunnus :liikeJaYhteisoTunnus :value))
    (fact "maksaja-yritys-osoitenimi" (-> maksaja-Yritys-postiosoite :osoitenimi :teksti) => (-> osoite :katu :value))
    (fact "maksaja-yritys-postinumero" (:postinumero maksaja-Yritys-postiosoite) => (-> osoite :postinumero :value))
    (fact "maksaja-yritys-postitoimipaikannimi" (:postitoimipaikannimi maksaja-Yritys-postiosoite) => (-> osoite :postitoimipaikannimi :value))

    ;; Osapuoli: Hakija
    (fact "hakija-etunimi" (:etunimi hakija-henkilo-nimi) => (-> nimi :etunimi :value))
    (fact "hakija-sukunimi" (:sukunimi hakija-henkilo-nimi) => (-> nimi :sukunimi :value))
    (fact "hakija-sahkopostiosoite" (:sahkopostiosoite hakija-Henkilo) => (-> yhteystiedot :email :value))
    (fact "hakija-puhelin" (:puhelin hakija-Henkilo) => (-> yhteystiedot :puhelin :value))
    (fact "hakija-nimi" (:nimi hakija-Yritys) => (-> yritys-nimi-ja-tunnus :yritysnimi :value))
    (fact "hakija-liikeJaYhteisotunnus" (:liikeJaYhteisotunnus hakija-Yritys) => (-> yritys-nimi-ja-tunnus :liikeJaYhteisoTunnus :value))
    (fact "hakija-osoitenimi" (-> hakija-yritys-Postiosoite :osoitenimi :teksti) => (-> osoite :katu :value))
    (fact "hakija-postinumero" (:postinumero hakija-yritys-Postiosoite) => (-> osoite :postinumero :value))
    (fact "hakija-postitoimipaikannimi" (:postitoimipaikannimi hakija-yritys-Postiosoite) => (-> osoite :postitoimipaikannimi :value))
    (fact "hakija-rooliKoodi" (:rooliKoodi hakija-Osapuoli) => rooliKoodi-Hakija)

    ;; Hakija, yksityinen henkilo -> Tama on testattu jo kohdassa "Maksaja, yksityinen henkilo" (muunnos on taysin sama)

    ;; Kayton alku/loppu pvm
    (fact "alkuPvm" alkuPvm => (to-xml-date-from-string (-> tyoaika :data :tyoaika-alkaa-pvm :value)))
    (fact "loppuPvm" loppuPvm => (to-xml-date-from-string (-> tyoaika :data :tyoaika-paattyy-pvm :value)))

    ;; Hankkeen kuvaus
    (fact "lupaAsianKuvaus" lupaAsianKuvaus => (-> hankkeen-kuvaus :data :kayttotarkoitus :value))
    (fact "vaadittuKytkin" (:vaadittuKytkin Sijoituslupaviite) => false)
    (fact "Sijoituslupaviite" (:tunniste Sijoituslupaviite) => (-> hankkeen-kuvaus :data :sijoitusLuvanTunniste :value))))
