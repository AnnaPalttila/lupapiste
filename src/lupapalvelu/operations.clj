(ns lupapalvelu.operations
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error fatal]]
            [sade.env :as env]
            [sade.core :refer :all]
            [lupapalvelu.action :refer [defquery]]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.document.poikkeamis-schemas]
            [lupapalvelu.document.ymparisto-schemas]
            [lupapalvelu.document.yleiset-alueet-schemas]
            [lupapalvelu.document.vesihuolto-schemas]
            [monger.operators :refer :all]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.permit :as permit]))

(def default-description "operations.tree.default-description")

(def- operation-tree-for-R
  ["Rakentaminen ja purkaminen"
   [["Uuden rakennuksen rakentaminen"
     [["Asuinrakennus" :asuinrakennus]
      ["Vapaa-ajan asuinrakennus" :vapaa-ajan-asuinrakennus]
      ["Varasto, sauna, autotalli tai muu talousrakennus" :varasto-tms]
      ["Julkinen rakennus" :julkinen-rakennus]
      ["Muun rakennuksen rakentaminen" :muu-uusi-rakentaminen]]]
    ["Rakennuksen korjaaminen tai muuttaminen"
     [["Rakennuksen laajentaminen tai korjaaminen" :laajentaminen]
      ["Perustusten tai kantavien rakenteiden muuttaminen tai korjaaminen" :perus-tai-kant-rak-muutos]
      ["Kayttotarkoituksen muutos" :kayttotark-muutos]
      ["Rakennuksen julkisivun tai katon muuttaminen" :julkisivu-muutos]
      ["Asuinhuoneiston jakaminen tai yhdistaminen" :jakaminen-tai-yhdistaminen]
      ["Markatilan laajentaminen" :markatilan-laajentaminen]
      ["Takan ja savuhormin rakentaminen" :takka-tai-hormi]
      ["Parvekkeen tai terassin lasittaminen" :parveke-tai-terassi]
      ["Muu rakennuksen muutostyo" :muu-laajentaminen]]]
    ["Rakennelman rakentaminen"
     [["Auto- tai grillikatos, vaja, kioski tai vastaava" :auto-katos]
      ["Masto, piippu, sailio, laituri tai vastaava" :masto-tms]
      ["Mainoslaite" :mainoslaite]
      ["Aita" :aita]
      ["Maalampokaivon poraaminen tai lammonkeruuputkiston asentaminen" :maalampo]
      ["Rakennuksen jatevesijarjestelman uusiminen" :jatevesi]
      ["Muun rakennelman rakentaminen" :muu-rakentaminen]]]
    ["Rakennuksen purkaminen" :purkaminen]
    ["Tyonjohtaja" :tyonjohtajan-nimeaminen-v2]
    ["Suunnittelija" :suunnittelijan-nimeaminen]
    ["Jatkoaika" :jatkoaika]
    ["aiemmalla-luvalla-hakeminen" :aiemmalla-luvalla-hakeminen]
    ["Aloitusoikeus" :aloitusoikeus]]])

(def- operation-tree-for-environment-R
  ["Elinympariston muuttaminen"
   [["Maisemaa muutava toimenpide"
     [["Kaivaminen, louhiminen tai maan tayttaminen" :kaivuu]
      ["Puun kaataminen" :puun-kaataminen]
      ["Muu maisemaa muuttava toimenpide" :muu-maisema-toimenpide]]]
    ["Tontti tai korttelialueen jarjestelymuutos"
     [["Tontin ajoliittyman muutos" :tontin-ajoliittyman-muutos]
      ["Paikoitusjarjestelyihin liittyvat muutokset" :paikoutysjarjestus-muutos]
      ["Korttelin yhteisiin alueisiin liittyva muutos" :kortteli-yht-alue-muutos]
      ["Muu-tontti-tai-korttelialueen-jarjestelymuutos" :muu-tontti-tai-kort-muutos]]]]])

(def- new-operation-tree-for-R
  ["Rakentaminen ja purkaminen"
   [["Uuden rakennuksen rakentaminen"
     [["kerrostalo-rivitalo" :kerrostalo-rivitalo]
      ["pientalo" :pientalo]
      ["Vapaa-ajan asuinrakennus" :vapaa-ajan-asuinrakennus]
      ["Varasto, sauna, autotalli tai muu talousrakennus" :varasto-tms]
      ["teollisuusrakennus" :teollisuusrakennus]
      ["Muun rakennuksen rakentaminen" :muu-uusi-rakentaminen]]]
    ["Rakennuksen-laajentaminen"
     [["kerrostalo-rt-laaj" :kerrostalo-rt-laaj]
      ["pientalo-laaj" :pientalo-laaj]
      ["vapaa-ajan-rakennus-laaj" :vapaa-ajan-rakennus-laaj]
      ["talousrakennus-laaj" :talousrakennus-laaj]
      ["teollisuusrakennus-laaj" :teollisuusrakennus-laaj]
      ["muu-rakennus-laaj" :muu-rakennus-laaj]]]
    ["Rakennuksen korjaaminen tai muuttaminen"
     [["kayttotark-muutos" :kayttotark-muutos]
      ["sisatila-muutos" :sisatila-muutos]
      ["Rakennuksen julkisivun tai katon muuttaminen" :julkisivu-muutos]
      ["Markatilan laajentaminen" :markatilan-laajentaminen]
      ["linjasaneeraus" :linjasaneeraus]
      ["Parvekkeen tai terassin lasittaminen" :parveke-tai-terassi]
      ["Perustusten tai kantavien rakenteiden muuttaminen tai korjaaminen" :perus-tai-kant-rak-muutos]
      ["Takan ja savuhormin rakentaminen" :takka-tai-hormi]
      ["Asuinhuoneiston jakaminen tai yhdistaminen" :jakaminen-tai-yhdistaminen]]]
    ["Rakennelman rakentaminen"
     [["Auto- tai grillikatos, vaja, kioski tai vastaava" :auto-katos]
      ["Masto, piippu, sailio, laituri tai vastaava" :masto-tms]
      ["Mainoslaite" :mainoslaite]
      ["Aita" :aita]
      ["Maalampokaivon poraaminen tai lammonkeruuputkiston asentaminen" :maalampo]
      ["Rakennuksen jatevesijarjestelman uusiminen" :jatevesi]]]
    ["Rakennuksen purkaminen" :purkaminen]
    ["Maisemaa muutava toimenpide"
     [["Puun kaataminen" :puun-kaataminen]
      ["tontin-jarjestelymuutos" :tontin-jarjestelymuutos]
      ["Muu-tontti-tai-korttelialueen-jarjestelymuutos" :muu-tontti-tai-kort-muutos]
      ["Kaivaminen, louhiminen tai maan tayttaminen" :kaivuu]
      ["Muu maisemaa muuttava toimenpide" :muu-maisema-toimenpide]]]
    ["rakennustyo-muutostoimenpiteet"
     [["Suunnittelija" :suunnittelijan-nimeaminen]
      ["Tyonjohtaja" :tyonjohtajan-nimeaminen-v2]
      ["rak-valm-tyo" :rak-valm-tyo]
      ["Aloitusoikeus" :aloitusoikeus]
      ["raktyo-aloit-loppuunsaat" :raktyo-aloit-loppuunsaat]]]]])

(def- operation-tree-for-YA
  ["yleisten-alueiden-luvat"
   [["sijoituslupa"
     [["pysyvien-maanalaisten-rakenteiden-sijoittaminen"
       [["vesi-ja-viemarijohtojen-sijoittaminen" :ya-sijoituslupa-vesi-ja-viemarijohtojen-sijoittaminen]
        ["maalampoputkien-sijoittaminen" :ya-sijoituslupa-maalampoputkien-sijoittaminen]
        ["kaukolampoputkien-sijoittaminen" :ya-sijoituslupa-kaukolampoputkien-sijoittaminen]
        ["sahko-data-ja-muiden-kaapelien-sijoittaminen" :ya-sijoituslupa-sahko-data-ja-muiden-kaapelien-sijoittaminen]
        ["rakennuksen-tai-sen-osan-sijoittaminen" :ya-sijoituslupa-rakennuksen-tai-sen-osan-sijoittaminen]]]
      ["pysyvien-maanpaallisten-rakenteiden-sijoittaminen"
       [["ilmajohtojen-sijoittaminen" :ya-sijoituslupa-ilmajohtojen-sijoittaminen]
        ["muuntamoiden-sijoittaminen" :ya-sijoituslupa-muuntamoiden-sijoittaminen]
        ["jatekatoksien-sijoittaminen" :ya-sijoituslupa-jatekatoksien-sijoittaminen]
        ["leikkipaikan-tai-koiratarhan-sijoittaminen" :ya-sijoituslupa-leikkipaikan-tai-koiratarhan-sijoittaminen]
        ["rakennuksen-pelastuspaikan-sijoittaminen" :ya-sijoituslupa-rakennuksen-pelastuspaikan-sijoittaminen]]]
     ["muu-sijoituslupa" :ya-sijoituslupa-muu-sijoituslupa]]]
    ["katulupa"
     [["kaivaminen-yleisilla-alueilla"
       [["vesi-ja-viemarityot" :ya-katulupa-vesi-ja-viemarityot]
        ["maalampotyot" :ya-katulupa-maalampotyot]
        ["kaukolampotyot" :ya-katulupa-kaukolampotyot]
        ["kaapelityot" :ya-katulupa-kaapelityot]
        ["kiinteiston-johto-kaapeli-ja-putkiliitynnat" :ya-katulupa-kiinteiston-johto-kaapeli-ja-putkiliitynnat]]]
      ["liikennealueen-rajaaminen-tyokayttoon"
       [["nostotyot" :ya-kayttolupa-nostotyot]
        ["vaihtolavat" :ya-kayttolupa-vaihtolavat]
        ["kattolumien-pudotustyot" :ya-kayttolupa-kattolumien-pudotustyot]
        ["muu-liikennealuetyo" :ya-kayttolupa-muu-liikennealuetyo]]]
      ["yleisen-alueen-rajaaminen-tyomaakayttoon"
       [["talon-julkisivutyot" :ya-kayttolupa-talon-julkisivutyot]
        ["talon-rakennustyot" :ya-kayttolupa-talon-rakennustyot]
        ["muu-tyomaakaytto" :ya-kayttolupa-muu-tyomaakaytto]]]]]
    ["kayttolupa"
     [["tapahtumat" :ya-kayttolupa-tapahtumat]
      ["harrastustoiminnan-jarjestaminen" :ya-kayttolupa-harrastustoiminnan-jarjestaminen]
      ["mainokset" :ya-kayttolupa-mainostus-ja-viitoitus]
      ["metsastys" :ya-kayttolupa-metsastys]
      ["vesistoluvat" :ya-kayttolupa-vesistoluvat]
      ["terassit" :ya-kayttolupa-terassit]
      ["kioskit" :ya-kayttolupa-kioskit]
      ["muu-kayttolupa" :ya-kayttolupa-muu-kayttolupa]]]
    ["jatkoaika" :ya-jatkoaika]]])

(def- operation-tree-for-P
  ["Poikkeusluvat ja suunnittelutarveratkaisut" :poikkeamis])

(def- operation-tree-for-Y
  ["Ymp\u00e4rist\u00f6luvat"
   (filterv identity ; TODO remove filter after pima feature is in production
     [; permit/YI
      ["Meluilmoitus" :meluilmoitus]

      ; at the moment permit/R
      (when (env/feature? :pima) ["Pima" :pima])

      ; permit/MAL
      ["maa-ainesten_ottaminen" :maa-aineslupa]

      ; permit/YL
      ["ympariston-pilaantumisen-vaara"
       [["uusi-toiminta" :yl-uusi-toiminta]
        ["olemassa-oleva-toiminta" :yl-olemassa-oleva-toiminta]
        ["toiminnan-muutos" :yl-toiminnan-muutos]]]

      ; permit/VVVL
      ["vapautus-vesijohdosta-ja-viemariin-liitymisvelvollisuudeseta"
       [["vesijohdosta" :vvvl-vesijohdosta]
        ["viemarista" :vvvl-viemarista]
        ["vesijohdosta-ja-viemarista" :vvvl-vesijohdosta-ja-viemarista]
        ["hulevesiviemarista" :vvvl-hulevesiviemarista]]]])])

(def operation-tree-for-KT ; aka kiinteistotoimitus aka maanmittaustoimitukset
  ["maanmittaustoimitukset"
   [["tonttijaon-hakeminen" :tonttijaon-hakeminen]
    ["tonttijaon-muutoksen-hakeminen" :tonttijaon-muutoksen-hakeminen]
    ["tontin-lohkominen" :tontin-lohkominen]
    ["tilan-rekisteroiminen-tontiksi" :tilan-rekisteroiminen-tontiksi]
    ["yhdistaminen" :yhdistaminen]
    ["halkominen" :halkominen]
    ["rasitetoimitus" :rasitetoimitus]
    ["tilusvaihto" :tilusvaihto]
    ["rajankaynnin-hakeminen" :rajankaynnin-hakeminen]
    ["rajannayton-hakeminen" :rajannayton-hakeminen]
    ["rakennuksen-sijainti" :rakennuksen-sijainti]
    ["ya-lohkominen" :ya-lohkominen]]])

(def operation-tree-for-MM
  ["maankayton-muutos" ; kaavat
  [["asemakaava-laadinta" :asemakaava-laadinta]
   ["asemakaava-muutos" :asemakaava-muutos]
   ["ranta-asemakaava-laadinta" :ranta-asemakaava-laadinta]
   ["ranta-asemakaava-muutos" :ranta-asemakaava-muutos]
   ["yleiskaava-laadinta" :yleiskaava-laadinta]
   ["yleiskaava-muutos" :yleiskaava-muutos]]])


(def operation-tree
  (filterv identity
    `[
      ~@(if (env/feature? :new-operations-tree)
          [new-operation-tree-for-R]
          [operation-tree-for-R operation-tree-for-environment-R])
      ~@[operation-tree-for-P
         (when (env/feature? :ymparisto) operation-tree-for-Y)
         operation-tree-for-YA]
      ~@(when (env/feature? :kiinteistonMuodostus)
          [operation-tree-for-KT operation-tree-for-MM])]))


(def schema-data-yritys-selected [[["_selected"] "yritys"]])

; Operations must be the same as in the tree structure above.
; Mappings to schemas and attachments are currently random.

(def- common-rakval-schemas ["hankkeen-kuvaus" "paatoksen-toimitus-rakval" "maksaja" "rakennuspaikka" "paasuunnittelija" "suunnittelija"])

(def- common-maanmittaus-schemas ["maksaja" "kiinteisto"])

(def- common-poikkeamis-schemas ["hankkeen-kuvaus" "maksaja" "poikkeusasian-rakennuspaikka"])

(def- common-yleiset-alueet-schemas ["yleiset-alueet-maksaja"])

(def- common-ymparistolupa-schemas ["ymp-maksaja" "rakennuspaikka"])

(def- common-vvvl-schemas ["hankkeen-kuvaus-vesihuolto" "vesihuolto-kiinteisto"])


(def- uuden_rakennuksen_liitteet [:paapiirustus
                                           [:asemapiirros
                                            :pohjapiirros
                                            :julkisivupiirros
                                            :leikkauspiirros]
                                           :rakennuspaikka
                                           [:selvitys_rakennuspaikan_perustamis_ja_pohjaolosuhteista]])

(def- rakennuksen_muutos_liitteet [:paapiirustus
                                            [:pohjapiirros
                                             :julkisivupiirros]])

(def- rakennuksen_laajennuksen_liitteet [:paapiirustus
                                                  [:asemapiirros
                                                   :pohjapiirros
                                                   :julkisivupiirros
                                                   :leikkauspiirros]])

(def- kaupunkikuva_toimenpide_liitteet [:paapiirustus
                                                 [:asemapiirros
                                                  :julkisivupiirros]])

(def- ya-katulupa-general {:schema "tyomaastaVastaava"
                                    :permit-type permit/YA
                                    :schema-data schema-data-yritys-selected
                                    :required (conj common-yleiset-alueet-schemas
                                                "yleiset-alueet-hankkeen-kuvaus-kaivulupa"
                                                "tyoaika")
                                    :attachments []
                                    :add-operation-allowed false
                                    :link-permit-required false
                                    :link-permit-verdict-required false
                                    :asianhallinta true})

(def- ya-kayttolupa-general {:schema "tyoaika"
                                      :permit-type permit/YA
                                      :required (conj common-yleiset-alueet-schemas
                                                  "yleiset-alueet-hankkeen-kuvaus-kayttolupa")
                                      :attachments []
                                      :add-operation-allowed false
                                      :link-permit-required false
                                      :link-permit-verdict-required false
                                      :asianhallinta false})

(def- ya-kayttolupa-with-tyomaastavastaava
  (update-in ya-kayttolupa-general [:required] conj "tyomaastaVastaava"))

(def- ya-sijoituslupa-general {:schema "yleiset-alueet-hankkeen-kuvaus-sijoituslupa"
                                        :permit-type permit/YA
                                        :required common-yleiset-alueet-schemas
                                        :attachments []
                                        :add-operation-allowed false
                                        :link-permit-required false
                                        :link-permit-verdict-required false
                                        :asianhallinta true})

(def ya-operations
  {:ya-kayttolupa-tapahtumat                                          ya-kayttolupa-general
   :ya-kayttolupa-harrastustoiminnan-jarjestaminen                    ya-kayttolupa-general
   :ya-kayttolupa-metsastys                                           ya-kayttolupa-general
   :ya-kayttolupa-vesistoluvat                                        ya-kayttolupa-general
   :ya-kayttolupa-terassit                                            ya-kayttolupa-general
   :ya-kayttolupa-kioskit                                             ya-kayttolupa-general
   :ya-kayttolupa-muu-kayttolupa                                      ya-kayttolupa-general
   :ya-kayttolupa-mainostus-ja-viitoitus  {:schema "mainosten-tai-viitoitusten-sijoittaminen"
                                           :permit-type permit/YA
                                           :required common-yleiset-alueet-schemas
                                           :attachments []
                                           :add-operation-allowed false
                                           :link-permit-required false
                                           :link-permit-verdict-required false
                                           :asianhallinta false}
   :ya-kayttolupa-nostotyot                                           ya-kayttolupa-with-tyomaastavastaava
   :ya-kayttolupa-vaihtolavat                                         ya-kayttolupa-with-tyomaastavastaava
   :ya-kayttolupa-kattolumien-pudotustyot                             ya-kayttolupa-with-tyomaastavastaava
   :ya-kayttolupa-muu-liikennealuetyo                                 ya-kayttolupa-with-tyomaastavastaava
   :ya-kayttolupa-talon-julkisivutyot                                 ya-kayttolupa-with-tyomaastavastaava
   :ya-kayttolupa-talon-rakennustyot                                  ya-kayttolupa-with-tyomaastavastaava
   :ya-kayttolupa-muu-tyomaakaytto                                    ya-kayttolupa-with-tyomaastavastaava
   :ya-katulupa-vesi-ja-viemarityot                                   ya-katulupa-general
   :ya-katulupa-maalampotyot                                          ya-katulupa-general
   :ya-katulupa-kaukolampotyot                                        ya-katulupa-general
   :ya-katulupa-kaapelityot                                           ya-katulupa-general
   :ya-katulupa-kiinteiston-johto-kaapeli-ja-putkiliitynnat           ya-katulupa-general
   :ya-sijoituslupa-vesi-ja-viemarijohtojen-sijoittaminen             ya-sijoituslupa-general
   :ya-sijoituslupa-maalampoputkien-sijoittaminen                     ya-sijoituslupa-general
   :ya-sijoituslupa-kaukolampoputkien-sijoittaminen                   ya-sijoituslupa-general
   :ya-sijoituslupa-sahko-data-ja-muiden-kaapelien-sijoittaminen      ya-sijoituslupa-general
   :ya-sijoituslupa-rakennuksen-tai-sen-osan-sijoittaminen            ya-sijoituslupa-general
   :ya-sijoituslupa-ilmajohtojen-sijoittaminen                        ya-sijoituslupa-general
   :ya-sijoituslupa-muuntamoiden-sijoittaminen                        ya-sijoituslupa-general
   :ya-sijoituslupa-jatekatoksien-sijoittaminen                       ya-sijoituslupa-general
   :ya-sijoituslupa-leikkipaikan-tai-koiratarhan-sijoittaminen        ya-sijoituslupa-general
   :ya-sijoituslupa-rakennuksen-pelastuspaikan-sijoittaminen          ya-sijoituslupa-general
   :ya-sijoituslupa-muu-sijoituslupa                                  ya-sijoituslupa-general
   :ya-jatkoaika                          {:schema "hankkeen-kuvaus-jatkoaika"
                                           :permit-type permit/YA
                                           :required (conj common-yleiset-alueet-schemas
                                                       "tyo-aika-for-jatkoaika")
                                           :attachments []
                                           :add-operation-allowed false
                                           :link-permit-required true
                                           :link-permit-verdict-required true
                                           :asianhallinta true}})

(def- ymparistolupa-attachments []) ; TODO
(def- ymparistolupa-operation
  {:schema "yl-hankkeen-kuvaus"
   :permit-type permit/YL
   :schema-data []
   :required common-ymparistolupa-schemas
   :attachments ymparistolupa-attachments
   :add-operation-allowed false
   :link-permit-required false
   :link-permit-verdict-required false
   :asianhallinta false})

(def yl-operations
  {:yl-uusi-toiminta ymparistolupa-operation
   :yl-olemassa-oleva-toiminta ymparistolupa-operation
   :yl-toiminnan-muutos ymparistolupa-operation})

(def operations
  (merge
    {:asuinrakennus               {:schema "uusiRakennus"
                                   :permit-type permit/R
                                   :schema-data [[["kaytto" "kayttotarkoitus"] schemas/yhden-asunnon-talot]
                                                 [["huoneistot" "0" "huoneistonumero"] "000"]
                                                 [["huoneistot" "0" "muutostapa"] "lis\u00e4ys"]]
                                   :required common-rakval-schemas
                                   :attachments uuden_rakennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false} ;TODO old op-tree, remove later
     :kerrostalo-rivitalo         {:schema "uusiRakennus"
                                   :permit-type permit/R
                                   :schema-data [[["kaytto" "kayttotarkoitus"] schemas/rivitalot]
                                                 [["huoneistot" "0" "huoneistonumero"] "000"]
                                                 [["huoneistot" "0" "muutostapa"] "lis\u00e4ys"]]
                                   :required common-rakval-schemas
                                   :attachments uuden_rakennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :pientalo                    {:schema "uusiRakennus"
                                   :permit-type permit/R
                                   :schema-data [[["kaytto" "kayttotarkoitus"] schemas/yhden-asunnon-talot]
                                                 [["huoneistot" "0" "huoneistonumero"] "000"]
                                                 [["huoneistot" "0" "muutostapa"] "lis\u00e4ys"]]
                                   :required common-rakval-schemas
                                   :attachments uuden_rakennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :vapaa-ajan-asuinrakennus    {:schema "uusi-rakennus-ei-huoneistoa"
                                   :permit-type permit/R
                                   :schema-data [[["kaytto" "kayttotarkoitus"] schemas/vapaa-ajan-asuinrakennus]]
                                   :required common-rakval-schemas
                                   :attachments uuden_rakennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :varasto-tms                 {:schema "uusi-rakennus-ei-huoneistoa"
                                   :permit-type permit/R
                                   :schema-data [[["kaytto" "kayttotarkoitus"] schemas/talousrakennus]]
                                   :required common-rakval-schemas
                                   :attachments uuden_rakennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :julkinen-rakennus           {:schema "uusiRakennus"
                                   :permit-type permit/R
                                   :schema-data [[["huoneistot" "0" "huoneistonumero"] "000"]
                                                 [["huoneistot" "0" "muutostapa"] "lis\u00e4ys"]]
                                   :required common-rakval-schemas
                                   :attachments uuden_rakennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false} ;TODO old op-tree, remove later
     :teollisuusrakennus          {:schema "uusiRakennus"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments uuden_rakennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :muu-uusi-rakentaminen       {:schema "uusiRakennus"
                                   :permit-type permit/R
                                   :schema-data [[["huoneistot" "0" "huoneistonumero"] "000"]
                                                 [["huoneistot" "0" "muutostapa"] "lis\u00e4ys"]]
                                   :required common-rakval-schemas
                                   :attachments uuden_rakennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :laajentaminen               {:schema "rakennuksen-laajentaminen"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false} ;TODO old op-tree, remove later
     :kerrostalo-rt-laaj          {:schema "rakennuksen-laajentaminen"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :pientalo-laaj               {:schema "rakennuksen-laajentaminen"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :vapaa-ajan-rakennus-laaj    {:schema "rakennuksen-laajentaminen"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :talousrakennus-laaj         {:schema "rakennuksen-laajentaminen"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :teollisuusrakennus-laaj     {:schema "rakennuksen-laajentaminen"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :muu-rakennus-laaj           {:schema "rakennuksen-laajentaminen"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :perus-tai-kant-rak-muutos   {:schema "rakennuksen-muuttaminen-ei-huoneistoja"
                                   :permit-type permit/R
                                   :schema-data [[["muutostyolaji"] schemas/perustusten-korjaus]]
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :kayttotark-muutos           {:schema "rakennuksen-muuttaminen"
                                   :permit-type permit/R
                                   :schema-data [[["muutostyolaji"] schemas/kayttotarkotuksen-muutos]]
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_muutos_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :sisatila-muutos             {:schema "rakennuksen-muuttaminen"
                                   :permit-type permit/R
                                   :schema-data [[["muutostyolaji"] schemas/kayttotarkotuksen-muutos]]
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_muutos_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :julkisivu-muutos            {:schema "rakennuksen-muuttaminen-ei-huoneistoja-ei-ominaisuuksia"
                                   :permit-type permit/R
                                   :schema-data [[["muutostyolaji"] schemas/muumuutostyo]]
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :jakaminen-tai-yhdistaminen  {:schema "rakennuksen-muuttaminen"
                                   :permit-type permit/R
                                   :schema-data [[["muutostyolaji"] schemas/muumuutostyo]]
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :markatilan-laajentaminen    {:schema "rakennuksen-muuttaminen-ei-huoneistoja-ei-ominaisuuksia"
                                   :permit-type permit/R
                                   :schema-data [[["muutostyolaji"] schemas/muumuutostyo]]
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :linjasaneeraus              {:schema "rakennuksen-muuttaminen"
                                   :permit-type permit/R
                                   :schema-data [[["muutostyolaji"] schemas/muumuutostyo]]
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :takka-tai-hormi             {:schema "rakennuksen-muuttaminen-ei-huoneistoja-ei-ominaisuuksia"
                                   :permit-type permit/R
                                   :schema-data [[["muutostyolaji"] schemas/muumuutostyo]]
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :parveke-tai-terassi         {:schema "rakennuksen-muuttaminen-ei-huoneistoja-ei-ominaisuuksia"
                                   :permit-type permit/R
                                   :schema-data [[["muutostyolaji"] schemas/muumuutostyo]]
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :muu-laajentaminen           {:schema "rakennuksen-muuttaminen"
                                   :permit-type permit/R
                                   :schema-data [[["muutostyolaji"] schemas/muumuutostyo]]
                                   :required common-rakval-schemas
                                   :attachments rakennuksen_laajennuksen_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :auto-katos                  {:schema "kaupunkikuvatoimenpide"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments kaupunkikuva_toimenpide_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :masto-tms                   {:schema "kaupunkikuvatoimenpide"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments kaupunkikuva_toimenpide_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :mainoslaite                 {:schema "kaupunkikuvatoimenpide"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments kaupunkikuva_toimenpide_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :aita                        {:schema "kaupunkikuvatoimenpide"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments kaupunkikuva_toimenpide_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :maalampo                    {:schema "kaupunkikuvatoimenpide"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments kaupunkikuva_toimenpide_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :jatevesi                    {:schema "kaupunkikuvatoimenpide"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments kaupunkikuva_toimenpide_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :muu-rakentaminen            {:schema "kaupunkikuvatoimenpide"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments kaupunkikuva_toimenpide_liitteet
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :purkaminen                  {:schema "purkaminen"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments [:muut [:selvitys_rakennusjatteen_maarasta_laadusta_ja_lajittelusta
                                                        :selvitys_purettavasta_rakennusmateriaalista_ja_hyvaksikaytosta]]
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :kaivuu                      {:schema "maisematyo"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments [:paapiirustus [:asemapiirros]]
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :puun-kaataminen             {:schema "maisematyo"
                                   :permit-type permit/R
                                   :required common-rakval-schemas
                                   :attachments [:paapiirustus [:asemapiirros]]
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :tontin-jarjestelymuutos     {:schema "maisematyo"
                                   :permit-type permit/R
                                   :required  common-rakval-schemas
                                   :attachments [:paapiirustus [:asemapiirros]]
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :muu-maisema-toimenpide      {:schema "maisematyo"
                                   :permit-type permit/R
                                   :required  common-rakval-schemas
                                   :attachments [:paapiirustus [:asemapiirros]]
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :tontin-ajoliittyman-muutos  {:schema "maisematyo"
                                   :permit-type permit/R
                                   :required  common-rakval-schemas
                                   :attachments [:paapiirustus [:asemapiirros]]
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :paikoutysjarjestus-muutos   {:schema "maisematyo"
                                   :permit-type permit/R
                                   :required  common-rakval-schemas
                                   :attachments [:paapiirustus [:asemapiirros]]
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :kortteli-yht-alue-muutos    {:schema "maisematyo"
                                   :permit-type permit/R
                                   :required  common-rakval-schemas
                                   :attachments [:paapiirustus [:asemapiirros]]
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :muu-tontti-tai-kort-muutos  {:schema "maisematyo"
                                   :permit-type permit/R
                                   :required  common-rakval-schemas
                                   :attachments [:paapiirustus [:asemapiirros]]
                                   :add-operation-allowed true
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :tonttijaon-hakeminen        {:schema "maankayton-muutos"
                                   :permit-type permit/KT
                                   :required common-maanmittaus-schemas
                                   :attachments []
                                   :add-operation-allowed false
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta false}
     :tonttijaon-muutoksen-hakeminen {:schema "maankayton-muutos"
                                      :permit-type permit/KT
                                      :required common-maanmittaus-schemas
                                      :attachments []
                                      :add-operation-allowed false
                                      :link-permit-required false
                                      :link-permit-verdict-required false
                                      :asianhallinta false}
     :tontin-lohkominen            {:schema "kiinteistotoimitus"
                                    :permit-type permit/KT
                                    :required common-maanmittaus-schemas
                                    :attachments []
                                    :add-operation-allowed false
                                    :link-permit-required false
                                    :link-permit-verdict-required false
                                    :asianhallinta false}
     :tilan-rekisteroiminen-tontiksi {:schema "kiinteistotoimitus"
                                      :permit-type permit/KT
                                      :required common-maanmittaus-schemas
                                      :attachments []
                                      :add-operation-allowed false
                                      :link-permit-required false
                                      :link-permit-verdict-required false
                                      :asianhallinta false}
     :yhdistaminen                  {:schema "kiinteistotoimitus"
                                     :permit-type permit/KT
                                     :required common-maanmittaus-schemas
                                     :attachments []
                                     :add-operation-allowed false
                                     :link-permit-required false
                                     :link-permit-verdict-required false
                                     :asianhallinta false}
     :halkominen                    {:schema "kiinteistotoimitus"
                                     :permit-type permit/KT
                                     :required common-maanmittaus-schemas
                                     :attachments []
                                     :add-operation-allowed false
                                     :link-permit-required false
                                     :link-permit-verdict-required false
                                     :asianhallinta false}
     :rasitetoimitus                {:schema "kiinteistotoimitus"
                                     :permit-type permit/KT
                                     :required common-maanmittaus-schemas
                                     :attachments []
                                     :add-operation-allowed false
                                     :link-permit-required false
                                     :link-permit-verdict-required false
                                     :asianhallinta false}
     :tilusvaihto                   {:schema "kiinteistotoimitus"
                                     :permit-type permit/KT
                                     :required common-maanmittaus-schemas
                                     :attachments []
                                     :add-operation-allowed false
                                     :link-permit-required false
                                     :link-permit-verdict-required false
                                     :asianhallinta false}
     :rajankaynnin-hakeminen       {:schema "kiinteistotoimitus"
                                    :permit-type permit/KT
                                    :required common-maanmittaus-schemas
                                    :attachments []
                                    :add-operation-allowed false
                                    :link-permit-required false
                                    :link-permit-verdict-required false
                                    :asianhallinta false}
     :rajannayton-hakeminen        {:schema "kiinteistotoimitus"
                                    :permit-type permit/KT
                                    :required common-maanmittaus-schemas
                                    :attachments []
                                    :add-operation-allowed false
                                    :link-permit-required false
                                    :link-permit-verdict-required false
                                    :asianhallinta false}
     :rakennuksen-sijainti         {:schema "kiinteistotoimitus"
                                    :permit-type permit/KT
                                    :required common-maanmittaus-schemas
                                    :attachments []
                                    :add-operation-allowed false
                                    :link-permit-required false
                                    :link-permit-verdict-required false
                                    :asianhallinta false}
     :ya-lohkominen                {:schema "kiinteistotoimitus"
                                    :permit-type permit/KT
                                    :required common-maanmittaus-schemas
                                    :attachments []
                                    :add-operation-allowed false
                                    :link-permit-required false
                                    :link-permit-verdict-required false
                                    :asianhallinta false}
     :poikkeamis                  {:schema "rakennushanke"
                                   :permit-type "P"
                                   :required  (conj common-poikkeamis-schemas "suunnittelutarveratkaisun-lisaosa")
                                   :attachments [:paapiirustus [:asemapiirros]]
                                   :add-operation-allowed false
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta true}
     :meluilmoitus                {:schema "meluilmoitus"
                                   :permit-type permit/YI
                                   :required ["ymp-ilm-kesto"]
                                   :attachments [:kartat [:kartta-melun-ja-tarinan-leviamisesta]]
                                   :add-operation-allowed false
                                   :link-permit-required false
                                   :link-permit-verdict-required false
                                   :asianhallinta true}
     ;     :pima                        {:schema "pima"
     ;                                   :permit-type permit/YL ; TODO
     ;                                   :required ["ymp-ilm-kesto-mini"]
     ;                                   :attachments []
     ;                                   :add-operation-allowed true
     ;                                   :link-permit-required false
    :maa-aineslupa               {:schema "maa-aineslupa-kuvaus"
                                  :permit-type permit/MAL
                                  :required ["ymp-maksaja" "rakennuspaikka"]
                                  :attachments []
                                  :link-permit-required false
                                  :link-permit-verdict-required false
                                  :asianhallinta false}
    :vvvl-vesijohdosta           {:schema "talousvedet"
                                  :permit-type permit/VVVL
                                  :required common-vvvl-schemas
                                  :attachments [:kartat [:kartta-melun-ja-tarinan-leviamisesta]]
                                  :add-operation-allowed false
                                  :link-permit-required false
                                  :link-permit-verdict-required false
                                  :asianhallinta false}
    :vvvl-viemarista             {:schema "jatevedet"
                                  :permit-type permit/VVVL
                                  :required common-vvvl-schemas
                                  :attachments [:kartat [:kartta-melun-ja-tarinan-leviamisesta]]
                                  :add-operation-allowed false
                                  :link-permit-required false
                                  :link-permit-verdict-required false
                                  :asianhallinta false}
    :vvvl-vesijohdosta-ja-viemarista {:schema "talousvedet"
                                      :permit-type permit/VVVL
                                      :required (conj common-vvvl-schemas "jatevedet")
                                      :attachments [:kartat [:kartta-melun-ja-tarinan-leviamisesta]]
                                      :add-operation-allowed false
                                      :link-permit-required false
                                      :link-permit-verdict-required false
                                      :asianhallinta false}
    :vvvl-hulevesiviemarista    {:schema "hulevedet"
                                 :permit-type permit/VVVL
                                 :required common-vvvl-schemas
                                 :attachments [:kartat [:kartta-melun-ja-tarinan-leviamisesta]]
                                 :add-operation-allowed false
                                 :link-permit-required false
                                 :link-permit-verdict-required false
                                 :asianhallinta false}

    :tyonjohtajan-nimeaminen     {:schema "hankkeen-kuvaus-minimum"
                                  :permit-type permit/R
                                  :required ["tyonjohtaja" "maksaja"]
                                  :attachments []
                                  :add-operation-allowed false
                                  :link-permit-required true
                                  :link-permit-verdict-required true
                                  :asianhallinta false}

    :tyonjohtajan-nimeaminen-v2  {:schema "tyonjohtaja-v2"
                                  :permit-type permit/R
                                  :required ["hankkeen-kuvaus-minimum"]
                                  :attachments []
                                  :add-operation-allowed false
                                  :link-permit-required true
                                  :link-permit-verdict-required false
                                  :asianhallinta false}

    :suunnittelijan-nimeaminen   {:schema "hankkeen-kuvaus-minimum"
                                  :permit-type permit/R
                                  :required ["suunnittelija" "maksaja"]
                                  :attachments []
                                  :add-operation-allowed false
                                  :link-permit-required true
                                  :link-permit-verdict-required true
                                  :asianhallinta false}

    :jatkoaika                   {:schema "hankkeen-kuvaus-minimum"
                                  :permit-type permit/R
                                  :required ["maksaja"]
                                  :attachments []
                                  :add-operation-allowed false
                                  :link-permit-required true
                                  :link-permit-verdict-required true
                                  :asianhallinta false}

    :aiemmalla-luvalla-hakeminen {:schema "hankkeen-kuvaus"
                                  :permit-type permit/R
                                  :required []
                                  :attachments []
                                  :add-operation-allowed false
                                  :link-permit-required false
                                  :link-permit-verdict-required false
                                  :asianhallinta false}

    :rak-valm-tyo                {:schema "maisematyo"
                                  :permit-type permit/R
                                  :required common-rakval-schemas
                                  :attachments [:paapiirustus [:asemapiirros]]
                                  :add-operation-allowed true
                                  :link-permit-required false
                                  :link-permit-verdict-required false
                                  :asianhallinta false}

    :aloitusoikeus               {:schema "aloitusoikeus"
                                  :permit-type permit/R
                                  :required ["maksaja"]
                                  :attachments []
                                  :add-operation-allowed false
                                  :link-permit-required true
                                  :link-permit-verdict-required true
                                  :asianhallinta false}
    :raktyo-aloit-loppuunsaat   {:schema "hankkeen-kuvaus-minimum"
                                 :permit-type permit/R
                                 :required ["maksaja"]
                                 :attachments []
                                 :add-operation-allowed false
                                 :link-permit-required true
                                 :link-permit-verdict-required true
                                 :asianhallinta false}
    :asemakaava-laadinta        {:schema "maankayton-muutos"
                                 :permit-type permit/MM
                                 :required common-maanmittaus-schemas
                                 :attachments []
                                 :add-operation-allowed false
                                 :link-permit-required false
                                 :link-permit-verdict-required false
                                 :asianhallinta true}
    :asemakaava-muutos          {:schema "maankayton-muutos"
                                 :permit-type permit/MM
                                 :required common-maanmittaus-schemas
                                 :attachments []
                                 :add-operation-allowed false
                                 :link-permit-required false
                                 :link-permit-verdict-required false
                                 :asianhallinta true}
    :ranta-asemakaava-laadinta  {:schema "maankayton-muutos"
                                 :permit-type permit/MM
                                 :required common-maanmittaus-schemas
                                 :attachments []
                                 :add-operation-allowed false
                                 :link-permit-required false
                                 :link-permit-verdict-required false
                                 :asianhallinta true}
    :ranta-asemakaava-muutos    {:schema "maankayton-muutos"
                                 :permit-type permit/MM
                                 :required common-maanmittaus-schemas
                                 :attachments []
                                 :add-operation-allowed false
                                 :link-permit-required false
                                 :link-permit-verdict-required false
                                 :asianhallinta true}
    :yleiskaava-laadinta        {:schema "maankayton-muutos"
                                 :permit-type permit/MM
                                 :required common-maanmittaus-schemas
                                 :attachments []
                                 :add-operation-allowed false
                                 :link-permit-required false
                                 :link-permit-verdict-required false
                                 :asianhallinta true}
    :yleiskaava-muutos          {:schema "maankayton-muutos"
                                 :permit-type permit/MM
                                 :required common-maanmittaus-schemas
                                 :attachments []
                                 :add-operation-allowed false
                                 :link-permit-required false
                                 :link-permit-verdict-required false
                                 :asianhallinta true}
    }
    ya-operations
    yl-operations))

;;
;; Functions
;;

(doseq [[op {:keys [permit-type]}] operations]
  (when-not permit-type
    (throw (Exception. (format "Operation %s does not have permit-type set." op)))))

(def link-permit-required-operations
  (reduce (fn [result [operation metadata]]
            (if (:link-permit-required metadata)
              (conj result operation)
              result)) #{} operations))

(defn get-operation-metadata
  "First form returns all metadata for operation. Second form returns value of given metadata."
  ([operation] (operations (keyword operation)))
  ([operation metadata] ((keyword metadata) (operations (keyword operation)))))

(defn permit-type-of-operation [operation]
  (get-operation-metadata operation :permit-type))

(defn- is-add-operation-allowed-for-operation [operation]
  (get-operation-metadata operation :add-operation-allowed))

(defn operations-filtered [filtering-fn only-addable?]
  (clojure.walk/postwalk
    (fn [node]
      (if (keyword? node)
        (when (filtering-fn node)
          ; Return operation keyword if permit type matches,
          ; and if the only-addable filtering is required, apply that.
          ; Otherwise return nil.
          (if only-addable?
            (when (is-add-operation-allowed-for-operation node)
              node)
            node))
        (if (string? node)
          ; A step in a path is returned as is
          node
          ; Not a keyword or string, must be a sequence. Take only paths that have operations.
          (let [filtered (filter identity node)]
            (when (or (> (count filtered) 1) (sequential? (first filtered)))
              filtered)))))
    operation-tree))

(defn- sort-operation-tree [target-tree]
  (keep identity
    (for [part operation-tree]
      (some #(when (= (first %) (first part)) % ) target-tree))))

(defn organization-operations [organization]
  (let [permitTypes (->> organization :scope (map :permitType) set)
        filtering-fn (fn [node] (permitTypes (permit-type-of-operation node)))]
    (sort-operation-tree
      (operations-filtered filtering-fn false))))

(defn selected-operations-for-organizations [organizations]
  (let [filter-fn                 (fn [org] (seq (:selected-operations org)))
        orgs-with-selected-ops    (filter filter-fn organizations)
        orgs-without-selected-ops (remove filter-fn organizations)
        ;; Resolving operation tree for organizations with "selected-operations" defined in db
        op-trees-for-orgs-with-selected-ops (if-not (empty? orgs-with-selected-ops)
                                              (let [selected-operations-arrays (map :selected-operations orgs-with-selected-ops)
                                                    selected-operations (-> selected-operations-arrays
                                                                          (#(apply concat %))
                                                                          (#(map keyword %))
                                                                          set)
                                                    filtering-fn (fn [node] (selected-operations node))]
                                                (operations-filtered filtering-fn false))
                                              [])
        ;; Operation tree for organizations with no "selected-operations" defined in db
        op-trees-for-orgs-without-selected-ops (if-not (empty? orgs-without-selected-ops)
                                                 (-> orgs-without-selected-ops
                                                   (#(map organization-operations %))
                                                   (#(apply concat %)))
                                                 [])]
    (sort-operation-tree
      (concat op-trees-for-orgs-with-selected-ops op-trees-for-orgs-without-selected-ops))))

(defn addable-operations [selected-operations permit-type]
  (let [selected-operations (set selected-operations)
        filtering-fn (fn [node] (and
                                  (or (empty? selected-operations) (selected-operations node))
                                  (= (name permit-type) (permit-type-of-operation node))))]
    (sort-operation-tree
      (operations-filtered filtering-fn true))))
