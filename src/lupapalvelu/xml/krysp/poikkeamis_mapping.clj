(ns lupapalvelu.xml.krysp.poikkeamis-mapping
  (:require [lupapalvelu.xml.krysp.mapping-common :as mapping-common]
            [lupapalvelu.permit :as permit]
            [sade.util :refer :all]
            [lupapalvelu.document.poikkeamis-canonical :refer [poikkeus-application-to-canonical]]
            [lupapalvelu.xml.emit :refer [element-to-xml]]))

(def kerrosalatieto {:tag :kerrosalatieto :child [{:tag :kerrosala :child [{:tag :pintaAla}
                                                  {:tag :paakayttotarkoitusKoodi}]}]})

(def abstractPoikkeamisType [{:tag :kasittelynTilatieto :child [mapping-common/tilamuutos]}
                             {:tag :luvanTunnistetiedot
                              :child [mapping-common/lupatunnus]}
                             {:tag :osapuolettieto
                              :child [mapping-common/osapuolet]}
                             {:tag :rakennuspaikkatieto
                              :child [mapping-common/rakennuspaikka]}
                             {:tag :toimenpidetieto :child [{:tag :Toimenpide :child [{:tag :kuvausKoodi }
                                                                                      kerrosalatieto
                                                                                      {:tag :tavoitetilatieto :child [{:tag :Tavoitetila :child [{:tag :paakayttotarkoitusKoodi}
                                                                                                                                               {:tag :asuinhuoneistojenLkm}
                                                                                                                                                {:tag :rakennuksenKerrosluku}
                                                                                                                                                {:tag :kokonaisala}
                                                                                                                                                kerrosalatieto]}]}]}]}
                             {:tag :lausuntotieto :child [mapping-common/lausunto]}
                             {:tag :liitetieto
                              :child [{:tag :Liite
                                       :child [{:tag :kuvaus :ns "yht"}
                                               {:tag :linkkiliitteeseen :ns "yht"}
                                               {:tag :muokkausHetki :ns "yht"}
                                               {:tag :versionumero :ns "yht"}
                                               {:tag :tekija :ns "yht"
                                                :child [{:tag :kuntaRooliKoodi}
                                                        {:tag :VRKrooliKoodi}
                                                        mapping-common/henkilo
                                                        mapping-common/yritys]}
                                               {:tag :tyyppi :ns "yht"}]}]}
                             {:tag :lisatietotieto :child [{:tag :Lisatieto :child [{:tag :asioimiskieli}]}]}
                             {:tag :asianTiedot :child [{:tag :Asiantiedot :child [{:tag :vahainenPoikkeaminen}
                                                                                   {:tag :poikkeamisasianKuvaus}
                                                                                   {:tag :suunnittelutarveasianKuvaus}]}]}
                             {:tag :kayttotapaus}])


(def poikkeamis_to_krysp
  {:tag :Popast
   :ns "ppst"
   :attr (merge {:xsi:schemaLocation
                 (str mapping-common/schemalocation-yht-2.1.0
                   "\nhttp://www.paikkatietopalvelu.fi/gml/poikkeamispaatos_ja_suunnittelutarveratkaisu
                      http://www.paikkatietopalvelu.fi/gml/poikkeamispaatos_ja_suunnittelutarveratkaisu/2.1.2/poikkeamispaatos_ja_suunnittelutarveratkaisu.xsd")
                 :xmlns:ppst "http://www.paikkatietopalvelu.fi/gml/poikkeamispaatos_ja_suunnittelutarveratkaisu"}
           mapping-common/common-namespaces)
   :child [{:tag :toimituksenTiedot :child mapping-common/toimituksenTiedot}
           {:tag :poikkeamisasiatieto :child [{:tag :Poikkeamisasia :child abstractPoikkeamisType}]}
           {:tag :suunnittelutarveasiatieto :child [{:tag :Suunnittelutarveasia :child abstractPoikkeamisType}]}]})

(defn save-application-as-krysp
  "Sends application to municipality backend. Returns a sequence of attachment file IDs that ware sent."
  [application lang submitted-application krysp-version output-dir begin-of-link]
  (let [subtype    (keyword (:permitSubtype application))
        krysp-polku (cond
                      (= subtype lupapalvelu.permit/poikkeamislupa)
                      [:Popast :poikkeamisasiatieto :Poikkeamisasia]
                      (= subtype lupapalvelu.permit/suunnittelutarveratkaisu)
                      [:Popast :suunnittelutarveasiatieto :Suunnittelutarveasia]
                      :default nil)
        krysp-polku-lausuntoon (conj krysp-polku :lausuntotieto)
        canonical-without-attachments  (poikkeus-application-to-canonical application lang)
        statement-given-ids (mapping-common/statements-ids-with-status
                              (get-in canonical-without-attachments krysp-polku-lausuntoon))
        statement-attachments (mapping-common/get-statement-attachments-as-canonical application begin-of-link statement-given-ids)
        attachments (mapping-common/get-attachments-as-canonical application begin-of-link)
        canonical-with-statement-attachments  (mapping-common/add-statement-attachments canonical-without-attachments statement-attachments krysp-polku-lausuntoon)
        canonical (assoc-in
                    canonical-with-statement-attachments
                    (conj krysp-polku :liitetieto)
                    attachments)
        xml (element-to-xml canonical poikkeamis_to_krysp)]

    (mapping-common/write-to-disk application attachments statement-attachments xml krysp-version output-dir)))

(permit/register-function permit/P :app-krysp-mapper save-application-as-krysp)
