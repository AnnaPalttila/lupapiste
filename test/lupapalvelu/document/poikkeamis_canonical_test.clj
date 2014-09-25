(ns lupapalvelu.document.poikkeamis-canonical-test
  (:require [lupapalvelu.factlet :as fl]
            [lupapalvelu.document.canonical-test-common :as ctc]
            [lupapalvelu.document.poikkeamis-canonical :refer :all]
            [lupapalvelu.document.poikkeamis-schemas :refer :all]
            [lupapalvelu.document.tools :as tools]
            [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]))


(def ^:private hakija {:id "523844e1da063788effc1c58"
                       :created 1379419361123
                       :schema-info {:approvable true
                                     :subtype "hakija"
                                     :name "hakija"
                                     :removable true
                                     :repeating true
                                     :version 1
                                     :type "party"
                                     :order 3}
                       :data {:_selected {:value "henkilo"}
                              :henkilo {:userId {:value "777777777777777777000020"}
                                        :henkilotiedot {:hetu {:value "210281-9988"}
                                                        :etunimi {:value "Pena"}
                                                        :sukunimi {:value "Panaani"}
                                                        :turvakieltoKytkin {:value true}}
                                        :yhteystiedot {:email {:value "pena@example.com"}
                                                       :puhelin {:value "0102030405"}}
                                        :osoite {:katu {:value "Paapankuja 12"}
                                                 :postinumero {:value "10203"}
                                                 :postitoimipaikannimi {:value "Piippola"}}}
                              :yritys {:yhteyshenkilo {:henkilotiedot {:etunimi {:value "Pena"}
                                                                       :sukunimi {:value "Panaani"}
                                                                       :turvakieltoKytkin {:value true}}
                                                       :yhteystiedot {:email {:value "pena@example.com"}
                                                                      :puhelin {:value "0102030405"}}}
                                       :osoite {:katu {:value "Paapankuja 12"}
                                                :postinumero {:value "10203"}
                                                :postitoimipaikannimi {:value "Piippola"}}}}})

(def ^:private uusi {:id "523844e1da063788effc1c57"
                     :created 1379419361123
                     :schema-info {:order 50
                                   :version 1
                                   :name "rakennushanke"
                                   :op {:id "523844e1da063788effc1c56"
                                        :name "poikkeamis"
                                        :created 1379419361123}
                                   :removable true}
                     :data {:toimenpiteet {:Toimenpide {:value "uusi"}
                                           :huoneistoja {:value "1"}
                                           :kayttotarkoitus {:value "011 yhden asunnon talot"}
                                           :kerroksia {:value "2"}
                                           :kerrosala {:value "200"}
                                           :kokonaisala {:value "220"}}}})

(def ^:private uusi2 {:id "523844e1da063788effc1c57"
                      :created 1379419361123
                      :schema-info {:order 50
                                    :version 1
                                    :name "rakennushanke"
                                    :op {:id "523844e1da063788effc1c56"
                                         :name "poikkeamis"
                                         :created 1379419361123}
                                    :removable true}
                      :data {:toimenpiteet  {:Toimenpide {:value "uusi"}
                                             :kayttotarkoitus {:value "941 talousrakennukset"}
                                             :kerroksia {:value "1"}
                                             :kerrosala {:value "25"}
                                             :kokonaisala {:value "30"}}}})

(def ^:private laajennus {:id "523844e1da063788effc1c57"
                          :created 1379419361123
                          :schema-info {:order 50
                                        :version 1
                                        :name "rakennushanke"
                                        :op {:id "523844e1da063788effc1c56"
                                             :name "poikkeamis"
                                             :created 1379419361123}
                                        :removable true}
                          :data {:kaytettykerrosala {:kayttotarkoitusKoodi {:value "013 muut erilliset talot"}
                                                     :pintaAla {:value "99"}}
                                 :toimenpiteet {:Toimenpide {:value "laajennus"}
                                                :kayttotarkoitus {:value "941 talousrakennukset"}
                                                :kerroksia {:value "1"}
                                                :kerrosala {:value "25"}
                                                :kokonaisala {:value "30"}}}})

(def ^:private hanke {:id "523844e1da063788effc1c59"
                      :created 1379419361123
                      :schema-info {:approvable true
                                    :name "hankkeen-kuvaus"
                                    :version 1
                                    :order 1}
                      :data {:kuvaus {:value "Omakotitalon ja tallin rakentaminen."}
                             :poikkeamat {:value "Alueelle ei voimassa olevaa kaava."}}})

(def ^:private maksaja {:id "523844e1da063788effc1c5a"
                        :created 1379419361123
                        :schema-info {:approvable true
                                      :name "maksaja"
                                      :removable true
                                      :repeating true
                                      :version 1
                                      :type "party"
                                      :order 6}
                        :data {:_selected {:value "yritys"}
                               :laskuviite {:value "LVI99997"}
                               :yritys {:liikeJaYhteisoTunnus {:value "1743842-0"}
                                        :osoite {:katu {:value "Koivukuja 2"}
                                                 :postinumero {:value "23500"}
                                                 :postitoimipaikannimi {:value "Helsinki"}}
                                        :yhteyshenkilo {:henkilotiedot {:etunimi {:value "Toimi"}
                                                                        :sukunimi {:value "Toimari"}}
                                                        :yhteystiedot {:email {:value "paajehu@yit.foo"}
                                                                       :puhelin {:value "020202"}}}
                                        :yritysnimi {:value "YIT"}}}})

(def ^:private rakennuspaikka {:id "523844e1da063788effc1c5b"
                               :created 1379419361123
                               :schema-info {:approvable true
                                             :name "poikkeusasian-rakennuspaikka"
                                             :version 1
                                             :order 2}
                               :data {:hallintaperuste {:value "oma"}
                                      :kaavanaste {:value "ei kaavaa"}
                                      :kiinteisto {:maaraalaTunnus {:value "0008"}
                                                   :tilanNimi {:value "Omatila"}
                                                   :rantaKytkin {:value true}}}})

(def ^:private paasuunnittelija {:id "523844e1da063788effc1c5d"
                                 :created 1379419361123
                                 :schema-info {:approvable true
                                               :name "paasuunnittelija"
                                               :removable false
                                               :version 1
                                               :type "party"
                                               :order 4}
                                 :data {:henkilotiedot {:etunimi {:value "Pena"}
                                                        :sukunimi {:value "Panaani"}
                                                        :hetu {:value "210281-9988"}}
                                        :osoite {:katu {:value "Paapankuja 12"}
                                                 :postinumero {:value "10203"}
                                                 :postitoimipaikannimi {:value "Piippola"}}
                                        :patevyys {:koulutusvalinta {:value "arkkitehti"}, :koulutus {:value "Arkkitehti"}
                                                   :valmistumisvuosi {:value "2010"}
                                                   :patevyysluokka {:value "AA"}
                                                   :kokemus {:value "3"}
                                                   :fise {:value "http://www.solita.fi"}}
                                        :userId {:value "777777777777777777000020"}
                                        :yhteystiedot {:email {:value "pena@example.com"}
                                                       :puhelin {:value "0102030405"}}}})

(def ^:private suunnittelija {:id "523844e1da063788effc1c5e"
                              :schema-info {:approvable true
                                            :name "suunnittelija"
                                            :removable true
                                            :repeating true
                                            :version 1
                                            :type "party"
                                            :order 5}
                              :created 1379419361123
                              :data {:henkilotiedot
                                     {:etunimi {:value "Pena"}
                                      :sukunimi {:value "Panaani"}
                                      :hetu {:value "210281-9988"}}
                                     :kuntaRoolikoodi {:value "KVV-suunnittelija"}
                                     :osoite {:katu {:value "Paapankuja 12"}
                                              :postinumero {:value "10203"}
                                              :postitoimipaikannimi {:value "Piippola"}}
                                     :patevyys {:koulutus {:value "El\u00e4m\u00e4n koulu"}
                                                :valmistumisvuosi {:value "2010"}
                                                :patevyysluokka {:value "C"}
                                                :kokemus {:value "3"}
                                                :fise {:value "http://www.solita.fi"}}
                                     :userId {:value "777777777777777777000020"}
                                     :yhteystiedot {:email {:value "pena@example.com"}
                                                    :puhelin {:value "0102030405"}}
                                     :yritys {:liikeJaYhteisoTunnus {:value "1743842-0"}
                                              :yritysnimi {:value "ewq"}}}})

(def ^:private lisaosa {:id "523844e1da063788effc1c5f"
                        :created 1379419361123
                        :schema-info {:name "suunnittelutarveratkaisun-lisaosa"
                                      :version 1
                                      :order 52}
                        :data {:kaavoituksen_ja_alueiden_tilanne {:rajoittuuko_tiehen {:value true}
                                                                  :tienkayttooikeus {:value true}}
                               :luonto_ja_kulttuuri {:kulttuurisesti_merkittava {:value true}}
                               :maisema {:metsan_reunassa {:value true}
                                         :metsassa {:value false}}
                               :merkittavyys {:rakentamisen_vaikutusten_merkittavyys {:value "Vain pient\u00e4 maisemallista haittaa."}}
                               :muut_vaikutukset {:etaisyys_viemariverkosta {:value "2000"}
                                                  :pohjavesialuetta {:value true}}
                               :vaikutukset_yhdyskuntakehykselle {:etaisyys_kauppaan {:value "12"}
                                                                  :etaisyys_kuntakeskuksen_palveluihin {:value "12"}
                                                                  :etaisyys_paivakotiin {:value "11"}
                                                                  :etaisyyys_alakouluun {:value "10"}
                                                                  :etaisyyys_ylakouluun {:value "20"}
                                                                  :muita_vaikutuksia {:value "Maisemallisesti talo tulee sijoittumaan m\u00e4en harjalle."}}
                               :virkistys_tarpeet {:ulkoilu_ja_virkistysaluetta_varattu {:value true}}}})

(def ^:private documents [hakija
                          uusi
                          uusi2
                          hanke
                          maksaja
                          rakennuspaikka
                          paasuunnittelija
                          suunnittelija
                          lisaosa])

(def ^:private documents-for-laajennus [hakija
                                        laajennus
                                        hanke
                                        maksaja
                                        rakennuspaikka
                                        paasuunnittelija
                                        suunnittelija
                                        lisaosa])

(def poikkari-hakemus
  {:schema-version 1
   :submitted 1379422973832
   :state "submitted"
   :auth [{:lastName "Panaani",
           :firstName "Pena",
           :username "pena",
           :type "owner",
           :role "owner",
           :id "777777777777777777000020"}]
   :location {:x 404174.92749023
              :y 6690687.4923706}
   :attachments []
   :statements ctc/statements
   :organization "753-P"
   :title "S\u00f6derkullantie 146"
   :operations [{:id "523844e1da063788effc1c56"
                 :name "poikkeamis"
                 :created 1379419361123}]
   :infoRequest false
   :opened 1379422973832
   :created 1379419361123
   :propertyId "75342700020063"
   :documents documents
   :_statements-seen-by {:777777777777777777000023 1379423134104}
   :_software_version "1.0.5"
   :modified 1379423133065
   :address "S\u00f6derkullantie 146"
   :permitType "P"
   :permitSubtype "poikkeamislupa"
   :id "LP-753-2013-00001"
   :municipality "753"
   :neighbors ctc/neighbors})

(ctc/validate-all-documents poikkari-hakemus)


(def suunnitelutarveratkaisu (assoc poikkari-hakemus :permitSubtype "suunnittelutarveratkaisu"))

(ctc/validate-all-documents suunnitelutarveratkaisu)


(testable-privates lupapalvelu.document.poikkeamis-canonical get-toimenpiteet)

(fl/fact*
  (let [canonical (poikkeus-application-to-canonical poikkari-hakemus "fi" ) => truthy
        Popast (:Popast canonical) => truthy
        toimituksenTiedot (:toimituksenTiedot Popast) => truthy
        aineistonnimi (:aineistonnimi toimituksenTiedot) => (:title poikkari-hakemus)
        aineistotoimittaja (:aineistotoimittaja toimituksenTiedot) => "lupapiste@solita.fi"
        tila (:tila toimituksenTiedot) => "keskener\u00e4inen"
        kuntakoodi (:kuntakoodi toimituksenTiedot) => (:municipality poikkari-hakemus)

        suunnittelutarveasiatieto (:suunnittelutarveasiatieto Popast) => nil
        poikkeamisasiatieto (:poikkeamisasiatieto Popast) => truthy
        Poikkeamisasia (:Poikkeamisasia poikkeamisasiatieto)
        ;abstarctPoikkeamistype
        kasittelynTilatieto (:kasittelynTilatieto Poikkeamisasia) => truthy
        Tilamuutos (-> kasittelynTilatieto first :Tilamuutos) => map?
        pvm (:pvm Tilamuutos) => "2013-09-17"

        kuntakoodi (:kuntakoodi Poikkeamisasia) => (:municipality poikkari-hakemus)

        luvanTunnistetiedot (:luvanTunnistetiedot Poikkeamisasia) => truthy
        LupaTunnus (:LupaTunnus luvanTunnistetiedot) => truthy
        muuTunnustieto (:muuTunnustieto LupaTunnus) => truthy
        MuuTunnus (:MuuTunnus muuTunnustieto) => truthy
        tunnus (:tunnus MuuTunnus) => "LP-753-2013-00001"
        sovellus (:sovellus MuuTunnus) => "Lupapiste"

        osapuolettieto (:osapuolettieto Poikkeamisasia) => truthy
        Osapuolet (:Osapuolet osapuolettieto) => truthy
        osapuolitieto (:osapuolitieto Osapuolet) => truthy

        ;Maksaja
        maksaja (some #(when (= (get-in % [:Osapuoli :VRKrooliKoodi] %) "maksaja") %) osapuolitieto) => truthy
        Osapuoli (:Osapuoli maksaja) => truthy
        _ (:turvakieltoKytkin Osapuoli) => false
        henkilo (:henkilo Osapuoli) => truthy
        _ (get-in henkilo [:nimi :etunimi]) => "Toimi"
        _ (get-in henkilo [:nimi :sukunimi]) => "Toimari"
        _ (:puhelin henkilo) => "020202"
        _ (:sahkopostiosoite henkilo) => "paajehu@yit.foo"
        yritys (:yritys Osapuoli) => truthy
        _ (:nimi yritys ) => "YIT"
        _ (:liikeJaYhteisotunnus yritys) => "1743842-0"
        postiosoite (:postiosoite yritys) => truthy
        _ (get-in postiosoite [:osoitenimi :teksti]) => "Koivukuja 2"
        _ (:postinumero postiosoite) => "23500"
        _ (:postitoimipaikannimi postiosoite) => "Helsinki"

        ;Hakija
        hakija (some #(when (= (get-in % [:Osapuoli :VRKrooliKoodi] %) "hakija") %) osapuolitieto) => truthy
        Osapuoli (:Osapuoli hakija) => truthy
        _ (:turvakieltoKytkin Osapuoli) => true
        henkilo (:henkilo Osapuoli) => truthy
        _ (get-in henkilo [:nimi :etunimi]) => "Pena"
        _ (get-in henkilo [:nimi :sukunimi]) => "Panaani"
        _ (:henkilotunnus henkilo) => "210281-9988"
        osoite (:osoite henkilo) => truthy
        _ (get-in osoite [:osoitenimi :teksti]) => "Paapankuja 12"
        _ (:postinumero osoite) => "10203"
        _ (:postitoimipaikannimi osoite) => "Piippola"
        _ (:puhelin henkilo) => "0102030405"
        _ (:sahkopostiosoite henkilo) => "pena@example.com"
        yritys (:yritys Osapuoli) => nil

        ;Paasuunnittelija
        suunnittelijatieto (:suunnittelijatieto Osapuolet) => truthy
        paasuunnittelija (some #(when (= (get-in % [:Suunnittelija :VRKrooliKoodi] %) "p\u00e4\u00e4suunnittelija") %) suunnittelijatieto) => truthy
        Suunnittelija (:Suunnittelija paasuunnittelija) => truthy
        henkilo (:henkilo Suunnittelija) => truthy
        _ (get-in henkilo [:nimi :etunimi]) => "Pena"
        _ (get-in henkilo [:nimi :sukunimi]) => "Panaani"
        _ (:henkilotunnus henkilo) => "210281-9988"
        _ (:puhelin henkilo) => "0102030405"
        _ (:sahkopostiosoite henkilo) => "pena@example.com"
        osoite (:osoite henkilo) => truthy
        _ (get-in osoite [:osoitenimi :teksti]) => "Paapankuja 12"
        _ (:postinumero osoite) => "10203"
        _ (:postitoimipaikannimi osoite) => "Piippola"
        _ (:koulutus Suunnittelija) => "Arkkitehti"
        _ (:patevyysvaatimusluokka Suunnittelija) => "AA"
        _ (:valmistumisvuosi Suunnittelija) => "2010"
        _ (:kokemusvuodet Suunnittelija) => "3"

        ;Suunnittelija
        suunnittelijatieto (:suunnittelijatieto Osapuolet) => truthy
        suunnittelija (some #(when (= (get-in % [:Suunnittelija :suunnittelijaRoolikoodi] %) "KVV-suunnittelija") %) suunnittelijatieto) => truthy
        Suunnittelija (:Suunnittelija suunnittelija) => truthy
        henkilo (:henkilo Suunnittelija) => truthy
        _ (get-in henkilo [:nimi :etunimi]) => "Pena"
        _ (get-in henkilo [:nimi :sukunimi]) => "Panaani"
        _ (:henkilotunnus henkilo) => "210281-9988"
        _ (:puhelin henkilo) => "0102030405"
        _ (:sahkopostiosoite henkilo) => "pena@example.com"
        osoite (:osoite henkilo) => truthy
        _ (get-in osoite [:osoitenimi :teksti]) => "Paapankuja 12"
        _ (:postinumero osoite) => "10203"
        _ (:postitoimipaikannimi osoite) => "Piippola"
        _ (:koulutus Suunnittelija) => "El\u00e4m\u00e4n koulu"
        _ (:patevyysvaatimusluokka Suunnittelija) => "C"
        _ (:valmistumisvuosi Suunnittelija) => "2010"
        _ (:kokemusvuodet Suunnittelija) => "3"

        rakennuspaikkatieto (:rakennuspaikkatieto Poikkeamisasia) => truthy
        Rakennuspaikkaf (first rakennuspaikkatieto) => truthy
        Rakennuspaikka (:Rakennuspaikka Rakennuspaikkaf) => truthy
        rakennuspaikanKiinteistotieto (:rakennuspaikanKiinteistotieto Rakennuspaikka) => truthy
        RakennuspaikanKiinteisto (:RakennuspaikanKiinteisto rakennuspaikanKiinteistotieto) => truthy
        kiinteistotieto (:kiinteistotieto RakennuspaikanKiinteisto) => truthy
        Kiinteisto (:Kiinteisto kiinteistotieto) => truthy
        tilannimi (:tilannimi Kiinteisto) => "Omatila"
        kiinteistotunnus (:kiinteistotunnus Kiinteisto) => "75342700020063"
        maaraalaTunnus (:maaraAlaTunnus Kiinteisto) => "M0008"
        rantaKytkin (:rantaKytkin Kiinteisto) => true
        kokotilaKytkin (:kokotilaKytkin RakennuspaikanKiinteisto) => false
        hallintaperuste (:hallintaperuste RakennuspaikanKiinteisto) => "oma"

        toimenpidetieto (:toimenpidetieto Poikkeamisasia) => truthy

        lausuntotieto (:lausuntotieto Poikkeamisasia) => truthy
        Lausunto (:Lausunto (first lausuntotieto)) => truthy
        viranomainen (:viranomainen Lausunto) => "Paloviranomainen"
        pyyntoPvm (:pyyntoPvm Lausunto) => "2013-09-17"
        lausuntotieto (:lausuntotieto Lausunto) => truthy
        annettu-lausunto (:Lausunto lausuntotieto) => truthy
        lausunnon-antanut-viranomainen (:viranomainen annettu-lausunto) => "Paloviranomainen"
        varsinainen-lausunto (:lausunto annettu-lausunto) => "Lausunto liitteen\u00e4."
        lausuntoPvm (:lausuntoPvm annettu-lausunto) => "2013-09-17"

        puoltotieto (:puoltotieto annettu-lausunto) => truthy
        Puolto (:Puolto puoltotieto) => truthy
        puolto (:puolto Puolto) => "puoltaa"
        lausuntoId (:id Lausunto) => "52385377da063788effc1e93"

        lisatietotieto (:lisatietotieto Poikkeamisasia) => truthy
        Lisatieto (:Lisatieto lisatietotieto) => truthy
        asioimiskieli (:asioimiskieli Lisatieto) => "suomi"
        suoramarkkinointikielto  (:suoramarkkinointikieltoKytkin Lisatieto) => nil?

        ;end of abstarctPoikkeamistype
        kaytttotapaus (:kayttotapaus Poikkeamisasia) => "Uusi poikkeamisasia"

        asianTiedot (:asianTiedot Poikkeamisasia) => truthy
        Asiantiedot (:Asiantiedot asianTiedot) => truthy
        vahainenPoikkeaminen (:vahainenPoikkeaminen Asiantiedot) => "Alueelle ei voimassa olevaa kaava."
        kuvaus (:poikkeamisasianKuvaus Asiantiedot) => "Omakotitalon ja tallin rakentaminen."]

    (fact "toimenpide-count" (count toimenpidetieto) => 2)

    (facts "yhden asunnon talot"
      (let [uusi (->> toimenpidetieto (some #(when (= (get-in % [:Toimenpide :tavoitetilatieto :Tavoitetila :paakayttotarkoitusKoodi]) "011 yhden asunnon talot") %)) :Toimenpide)
            tavoitetilatieto (:tavoitetilatieto uusi) => truthy
            Tavoitetila (:Tavoitetila tavoitetilatieto) => truthy
            kerrosalatieto (:kerrosalatieto Tavoitetila) => truthy
            kerrosala (:kerrosala kerrosalatieto) => truthy]
        (fact "rakennustunnus" (:rakennustunnus uusi) => nil)
        (fact "liitetieto" (:liitetieto uusi) => nil)
        (fact "kuvauskoodi" (:kuvausKoodi uusi) => "uusi")
        (fact "kerrosalatieto" (:kerrosalatieto uusi) => nil)
        (fact "paakayttotarkoitusKoodi" (:paakayttotarkoitusKoodi Tavoitetila) => "011 yhden asunnon talot")
        (fact "rakennuksenKerrosluku" (:rakennuksenKerrosluku Tavoitetila) => "2")
        (fact "kokonaisala" (:kokonaisala Tavoitetila) => "220")
        (fact "huoneistoja" (:asuinhuoneitojenLkm Tavoitetila) => "1")
        (fact "pinta-ala" (:pintaAla kerrosala) => "200")
        (fact "tavoite-kerrosala" (:kerrosala Tavoitetila) => "200") ; 2.1.3
        (fact "paakayttotarkoitusKoodi" (:paakayttotarkoitusKoodi kerrosala) => "011 yhden asunnon talot")))

    (facts "talousrakennus"
      (let [uusit (->> toimenpidetieto (some #(when (= (get-in % [:Toimenpide :tavoitetilatieto :Tavoitetila :paakayttotarkoitusKoodi]) "941 talousrakennukset") %) ) :Toimenpide)
            tavoitetilatieto (:tavoitetilatieto uusit) => truthy
            Tavoitetila (:Tavoitetila tavoitetilatieto) => truthy
            kerrosalatieto (:kerrosalatieto Tavoitetila) => truthy
            kerrosala (:kerrosala kerrosalatieto) => truthy]
        (fact "rakennustunnus" (:rakennustunnus uusit) => nil)
        (fact "liitetieto" (:liitetieto uusit) => nil)
        (fact "kuvauskoodi" (:kuvausKoodi uusit) => "uusi")
        (fact "kerrosalatieto" (:kerrosalatieto uusit) => nil)
        (fact "paakayttotarkoitusKoodi" (:paakayttotarkoitusKoodi Tavoitetila) => "941 talousrakennukset")
        (fact "rakennuksenKerrosluku" (:rakennuksenKerrosluku Tavoitetila) => "1")
        (fact "kokonaisala" (:kokonaisala Tavoitetila) => "30")
        (fact "huoneistoja" (:asuinhuoneitojenLkm Tavoitetila) => nil)
        (fact "pintala" (:pintaAla kerrosala) => "25")
        (fact "tavoite-kerrosala" (:kerrosala Tavoitetila) => "25") ; 2.1.3
        (fact "paakayttotarkoitusKoodi" (:paakayttotarkoitusKoodi kerrosala) => "941 talousrakennukset")))

    (facts "laajennus"
      (let [laajennus-tp (-> [laajennus] tools/unwrapped get-toimenpiteet first :Toimenpide)
            kerrosalatieto (:kerrosalatieto laajennus-tp) => truthy
            kerrosala (:kerrosala kerrosalatieto) => truthy
            tavoitetilatieto (:tavoitetilatieto laajennus-tp) => truthy
            Tavoitetila (:Tavoitetila tavoitetilatieto) => truthy
            tavoite-kerrosalatieto (:kerrosalatieto Tavoitetila) => truthy
            tavoite-kerrosala (:kerrosala tavoite-kerrosalatieto) => truthy]

        (fact "rakennustunnus" (:rakennustunnus laajennus-tp) => nil)
        (fact "liitetieto" (:liitetieto laajennus-tp) => nil)
        (fact "kuvauskoodi" (:kuvausKoodi laajennus-tp) => "laajennus")
        (fact "pintala" (:pintaAla kerrosala) => "99")
        (fact "paakayttotarkoitusKoodi" (:paakayttotarkoitusKoodi kerrosala) => "013 muut erilliset talot")
        (fact "paakayttotarkoitusKoodi" (:paakayttotarkoitusKoodi Tavoitetila) => "941 talousrakennukset")
        (fact "rakennuksenKerrosluku" (:rakennuksenKerrosluku Tavoitetila) => "1")
        (fact "tavoite-kokonaisala" (:kokonaisala Tavoitetila) => "30")
        (fact "tavoite-huoneistoja" (:asuinhuoneitojenLkm Tavoitetila) => nil)
        (fact "pinta-ala" (:pintaAla tavoite-kerrosala) => "25")
        (fact "tavoite-kerrosala" (:kerrosala Tavoitetila) => "25") ; 2.1.3
        (fact "paakayttotarkoitusKoodi" (:paakayttotarkoitusKoodi tavoite-kerrosala) => "941 talousrakennukset")))

    ))


;Suunnitelutarveratkaisu

(fl/fact*
  (let [application (tools/unwrapped suunnitelutarveratkaisu)
        canonical (poikkeus-application-to-canonical application "fi" ) => truthy
        Popast (:Popast canonical) => truthy
        toimituksenTiedot (:toimituksenTiedot Popast) => truthy
        aineistonnimi (:aineistonnimi toimituksenTiedot) => (:title suunnitelutarveratkaisu)
        aineistotoimittaja (:aineistotoimittaja toimituksenTiedot) => "lupapiste@solita.fi"
        tila (:tila toimituksenTiedot) => "keskener\u00e4inen"
        kuntakoodi (:kuntakoodi toimituksenTiedot) => (:municipality suunnitelutarveratkaisu)

        suunnittelutarveasiatieto (:suunnittelutarveasiatieto Popast) => truthy
        poikkeamisasiatieto (:poikkeamisasiatieto Popast) => nil
        Suunnittelutarveasia (:Suunnittelutarveasia suunnittelutarveasiatieto)
        ;abstarctPoikkeamistype
        kasittelynTilatieto (:kasittelynTilatieto Suunnittelutarveasia) => truthy
        Tilamuutos (-> kasittelynTilatieto first :Tilamuutos) => map?
        pvm (:pvm Tilamuutos) => "2013-09-17"

        kuntakoodi (:kuntakoodi Suunnittelutarveasia) => (:municipality poikkari-hakemus)

        luvanTunnistetiedot (:luvanTunnistetiedot Suunnittelutarveasia) => truthy
        LupaTunnus (:LupaTunnus luvanTunnistetiedot) => truthy
        muuTunnustieto (:muuTunnustieto LupaTunnus) => truthy
        MuuTunnus (:MuuTunnus muuTunnustieto) => truthy
        tunnus (:tunnus MuuTunnus) => "LP-753-2013-00001"
        sovellus (:sovellus MuuTunnus) => "Lupapiste"

        osapuolettieto (:osapuolettieto Suunnittelutarveasia) => truthy
        Osapuolet (:Osapuolet osapuolettieto) => truthy
        osapuolitieto (:osapuolitieto Osapuolet) => truthy

        ;Maksaja
        maksaja (some #(when (= (get-in % [:Osapuoli :VRKrooliKoodi] %) "maksaja") %) osapuolitieto) => truthy
        Osapuoli (:Osapuoli maksaja) => truthy
        _ (:turvakieltoKytkin Osapuoli) => false
        henkilo (:henkilo Osapuoli) => truthy
        _ (get-in henkilo [:nimi :etunimi]) => "Toimi"
        _ (get-in henkilo [:nimi :sukunimi]) => "Toimari"
        _ (:puhelin henkilo) => "020202"
        _ (:sahkopostiosoite henkilo) => "paajehu@yit.foo"
        yritys (:yritys Osapuoli) => truthy
        _ (:nimi yritys ) => "YIT"
        _ (:liikeJaYhteisotunnus yritys) => "1743842-0"
        postiosoite (:postiosoite yritys) => truthy
        _ (get-in postiosoite [:osoitenimi :teksti]) => "Koivukuja 2"
        _ (:postinumero postiosoite) => "23500"
        _ (:postitoimipaikannimi postiosoite) => "Helsinki"

        ;Hakija
        hakija (some #(when (= (get-in % [:Osapuoli :VRKrooliKoodi] %) "hakija") %) osapuolitieto) => truthy
        Osapuoli (:Osapuoli hakija) => truthy
        _ (:turvakieltoKytkin Osapuoli) => true
        henkilo (:henkilo Osapuoli) => truthy
        _ (get-in henkilo [:nimi :etunimi]) => "Pena"
        _ (get-in henkilo [:nimi :sukunimi]) => "Panaani"
        _ (:henkilotunnus henkilo) => "210281-9988"
        osoite (:osoite henkilo) => truthy
        _ (get-in osoite [:osoitenimi :teksti]) => "Paapankuja 12"
        _ (:postinumero osoite) => "10203"
        _ (:postitoimipaikannimi osoite) => "Piippola"
        _ (:puhelin henkilo) => "0102030405"
        _ (:sahkopostiosoite henkilo) => "pena@example.com"
        yritys (:yritys Osapuoli) => nil

        ;Paassuunnitelija
        suunnittelijatieto (:suunnittelijatieto Osapuolet) => truthy
        paasuunnittelija (some #(when (= (get-in % [:Suunnittelija :VRKrooliKoodi] %) "p\u00e4\u00e4suunnittelija") %) suunnittelijatieto) => truthy
        Suunnittelija (:Suunnittelija paasuunnittelija) => truthy
        henkilo (:henkilo Suunnittelija) => truthy
        _ (get-in henkilo [:nimi :etunimi]) => "Pena"
        _ (get-in henkilo [:nimi :sukunimi]) => "Panaani"
        _ (:puhelin henkilo) => "0102030405"
        _ (:sahkopostiosoite henkilo) => "pena@example.com"
        osoite (:osoite henkilo) => truthy
        _ (get-in osoite [:osoitenimi :teksti]) => "Paapankuja 12"
        _ (:postinumero osoite) => "10203"
        _ (:postitoimipaikannimi osoite) => "Piippola"
        _ (:koulutus Suunnittelija) => "Arkkitehti"
        _ (:patevyysvaatimusluokka Suunnittelija) => "AA"

        ;Suunnitelija
        suunnittelijatieto (:suunnittelijatieto Osapuolet) => truthy
        suunnittelija (some #(when (= (get-in % [:Suunnittelija :suunnittelijaRoolikoodi] %) "KVV-suunnittelija") %) suunnittelijatieto) => truthy
        Suunnittelija (:Suunnittelija suunnittelija) => truthy
        henkilo (:henkilo Suunnittelija) => truthy
        _ (get-in henkilo [:nimi :etunimi]) => "Pena"
        _ (get-in henkilo [:nimi :sukunimi]) => "Panaani"
        _ (:puhelin henkilo) => "0102030405"
        _ (:sahkopostiosoite henkilo) => "pena@example.com"
        osoite (:osoite henkilo) => truthy
        _ (get-in osoite [:osoitenimi :teksti]) => "Paapankuja 12"
        _ (:postinumero osoite) => "10203"
        _ (:postitoimipaikannimi osoite) => "Piippola"
        _ (:koulutus Suunnittelija) => "El\u00e4m\u00e4n koulu"
        _ (:patevyysvaatimusluokka Suunnittelija) => "C"

        rakennuspaikkatieto (:rakennuspaikkatieto Suunnittelutarveasia) => truthy
        Rakennuspaikkaf (first rakennuspaikkatieto) => truthy
        Rakennuspaikka (:Rakennuspaikka Rakennuspaikkaf) => truthy
        rakennuspaikanKiinteistotieto (:rakennuspaikanKiinteistotieto Rakennuspaikka) => truthy
        RakennuspaikanKiinteisto (:RakennuspaikanKiinteisto rakennuspaikanKiinteistotieto) => truthy
        kiinteistotieto (:kiinteistotieto RakennuspaikanKiinteisto) => truthy
        Kiinteisto (:Kiinteisto kiinteistotieto) => truthy
        tilannimi (:tilannimi Kiinteisto) => "Omatila"
        kiinteistotunnus (:kiinteistotunnus Kiinteisto) => "75342700020063"
        maaraalaTunnus (:maaraAlaTunnus Kiinteisto) => "M0008"
        rantaKytkin (:rantaKytkin Kiinteisto) => true
        kokotilaKytkin (:kokotilaKytkin RakennuspaikanKiinteisto) => false
        hallintaperuste (:hallintaperuste RakennuspaikanKiinteisto) => "oma"

        toimenpidetieto (:toimenpidetieto Suunnittelutarveasia) => truthy

        lausuntotieto (:lausuntotieto Suunnittelutarveasia) => truthy
        Lausunto (:Lausunto (first lausuntotieto)) => truthy
        viranomainen (:viranomainen Lausunto) => "Paloviranomainen"
        pyyntoPvm (:pyyntoPvm Lausunto) => "2013-09-17"
        lausuntotieto (:lausuntotieto Lausunto) => truthy
        annettu-lausunto (:Lausunto lausuntotieto) => truthy
        lausunnon-antanut-viranomainen (:viranomainen annettu-lausunto) => "Paloviranomainen"
        varsinainen-lausunto (:lausunto annettu-lausunto) => "Lausunto liitteen\u00e4."
        lausuntoPvm (:lausuntoPvm annettu-lausunto) => "2013-09-17"

        puoltotieto (:puoltotieto annettu-lausunto) => truthy
        Puolto (:Puolto puoltotieto) => truthy
        puolto (:puolto Puolto) => "puoltaa"
        lausuntoId (:id Lausunto) => "52385377da063788effc1e93"

        lisatietotieto (:lisatietotieto Suunnittelutarveasia) => truthy
        Lisatieto (:Lisatieto lisatietotieto) => truthy
        asioimiskieli (:asioimiskieli Lisatieto) => "suomi"
        suoramarkkinointikielto  (:suoramarkkinointikieltoKytkin Lisatieto) => nil?

        ;end of abstarctPoikkeamistype
        kaytttotapaus (:kayttotapaus Suunnittelutarveasia) => "Uusi suunnittelutarveasia"

        asianTiedot (:asianTiedot Suunnittelutarveasia) => truthy
        Asiantiedot (:Asiantiedot asianTiedot) => truthy
        vahainenPoikkeaminen (:vahainenPoikkeaminen Asiantiedot) => "Alueelle ei voimassa olevaa kaava."
        kuvaus (:suunnittelutarveasianKuvaus Asiantiedot) => "Omakotitalon ja tallin rakentaminen."]

    (fact "toimenpide-count" (count toimenpidetieto) => 2)

    (facts "yhden asunnon talot"
      (let [uusi (->> toimenpidetieto (some #(when (= (get-in % [:Toimenpide :tavoitetilatieto :Tavoitetila :paakayttotarkoitusKoodi]) "011 yhden asunnon talot") %)) :Toimenpide)
            tavoitetilatieto (:tavoitetilatieto uusi) => truthy
            Tavoitetila (:Tavoitetila tavoitetilatieto) => truthy
            kerrosalatieto (:kerrosalatieto Tavoitetila) => truthy
            kerrosala (:kerrosala kerrosalatieto) => truthy]

        (fact "rakennustunnus" (:rakennustunnus uusi) => nil)
        (fact "liitetieto" (:liitetieto uusi) => nil)
        (fact "kuvauskoodi" (:kuvausKoodi uusi) => "uusi")
        (fact "kerrosalatieto" (:kerrosalatieto uusi) => nil)
        (fact "paakayttotarkoitusKoodi" (:paakayttotarkoitusKoodi Tavoitetila) => "011 yhden asunnon talot")
        (fact "rakennuksenKerrosluku" (:rakennuksenKerrosluku Tavoitetila) => "2")
        (fact "kokonaisala" (:kokonaisala Tavoitetila) => "220")
        (fact "huoneistoja" (:asuinhuoneitojenLkm Tavoitetila) => "1")
        (fact "pinta-ala" (:pintaAla kerrosala) => "200")
        (fact "tavoite-kerrosala" (:kerrosala Tavoitetila) => "200") ; 2.1.3
        (fact "paakayttotarkoitusKoodi" (:paakayttotarkoitusKoodi kerrosala) => "011 yhden asunnon talot")))

    (facts "talousrakennus"
      (let [uusit (->> toimenpidetieto (some #(when (= (get-in % [:Toimenpide :tavoitetilatieto :Tavoitetila :paakayttotarkoitusKoodi]) "941 talousrakennukset") %)) :Toimenpide)
            tavoitetilatieto (:tavoitetilatieto uusit) => truthy
            Tavoitetila (:Tavoitetila tavoitetilatieto) => truthy
            kerrosalatieto (:kerrosalatieto Tavoitetila) => truthy
            kerrosala (:kerrosala kerrosalatieto) => truthy]
        (fact "rakennustunnus" (:rakennustunnus uusit) => nil)
        (fact "liitetieto" (:liitetieto uusit) => nil)
        (fact "kerrosalatieto" (:kerrosalatieto uusit) => nil)
        (fact "kuvauskoodi" (:kuvausKoodi uusit) => "uusi")
        (fact "paakayttotarkoitusKoodi" (:paakayttotarkoitusKoodi Tavoitetila) => "941 talousrakennukset")
        (fact "rakennuksenKerrosluku" (:rakennuksenKerrosluku Tavoitetila) => "1")
        (fact "kokonaisala" (:kokonaisala Tavoitetila) => "30")
        (fact "huoneistoja" (:asuinhuoneitojenLkm Tavoitetila) => nil)
        (fact "tavoite-pintala" (:pintaAla kerrosala) => "25")
        (fact "tavoite-kerrosala" (:kerrosala Tavoitetila) => "25") ; 2.1.3
        (fact "paakayttotarkoitusKoodi" (:paakayttotarkoitusKoodi kerrosala) => "941 talousrakennukset")))

    (facts "laajennus"
      (let [laajennus-tp (-> [laajennus] tools/unwrapped get-toimenpiteet first :Toimenpide)
            kerrosalatieto (:kerrosalatieto laajennus-tp) => truthy
            kerrosala (:kerrosala kerrosalatieto) => truthy
            tavoitetilatieto (:tavoitetilatieto laajennus-tp) => truthy
            Tavoitetila (:Tavoitetila tavoitetilatieto) => truthy
            tavoite-kerrosalatieto (:kerrosalatieto Tavoitetila) => truthy
            tavoite-kerrosala (:kerrosala tavoite-kerrosalatieto) => truthy]

        (fact "rakennustunnus" (:rakennustunnus laajennus-tp) => nil)
        (fact "kuvauskoodi" (:kuvausKoodi laajennus-tp) => "laajennus")
        (fact "pinta-ala" (:pintaAla kerrosala) => "99")
        (fact "paakayttotarkoitusKoodi" (:paakayttotarkoitusKoodi kerrosala) => "013 muut erilliset talot")
        (fact "paakayttotarkoitusKoodi" (:paakayttotarkoitusKoodi Tavoitetila) => "941 talousrakennukset")
        (fact "rakennuksenKerrosluku" (:rakennuksenKerrosluku Tavoitetila) => "1")
        (fact "kokonaisala" (:kokonaisala Tavoitetila) => "30")
        (fact "huoneistoja" (:asuinhuoneitojenLkm Tavoitetila) => nil)
        (fact "tavoite-pintala" (:pintaAla tavoite-kerrosala) => "25") ; 2.1.2
        (fact "tavoite-kerrosala" (:kerrosala Tavoitetila) => "25") ; 2.1.3
        (fact "tavoite-paakayttotarkoitusKoodi" (:paakayttotarkoitusKoodi tavoite-kerrosala) => "941 talousrakennukset")))))
