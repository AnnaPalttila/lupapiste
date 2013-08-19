(ns lupapalvelu.xml.krysp.rakennuslupa-mapping
  (:use  [clojure.data.xml]
         [clojure.java.io]
         [sade.util]
         [lupapalvelu.document.canonical-common :only [to-xml-datetime]]
         [lupapalvelu.document.rakennuslupa_canonical :only [application-to-canonical]]
         [lupapalvelu.xml.emit :only [element-to-xml]]
         [lupapalvelu.xml.krysp.validator :only [validate]])
  (:require [lupapalvelu.xml.krysp.mapping-common :as mapping-common]
         #_[sade.env :as env]
         [me.raynes.fs :as fs]
         [lupapalvelu.ke6666 :as ke6666]
         [lupapalvelu.core :as core]
         [lupapalvelu.mongo :as mongo])
  #_(:import [java.util.zip ZipOutputStream ZipEntry]))

;RakVal

(def ^:private huoneisto {:tag :huoneisto
                          :child [{:tag :muutostapa}
                                  {:tag :huoneluku}
                                  {:tag :keittionTyyppi}
                                  {:tag :huoneistoala}
                                  {:tag :varusteet
                                   :child [{:tag :WCKytkin}
                                           {:tag :ammeTaiSuihkuKytkin}
                                           {:tag :saunaKytkin}
                                           {:tag :parvekeTaiTerassiKytkin}
                                           {:tag :lamminvesiKytkin}]}
                                  {:tag :huoneistonTyyppi}
                                  {:tag :huoneistotunnus
                                   :child [{:tag :porras}
                                           {:tag :huoneistonumero}
                                           {:tag :jakokirjain}]}]})


(def yht-rakennus [{:tag :yksilointitieto :ns "yht"}
                   {:tag :alkuHetki :ns "yht"}
                   mapping-common/sijantitieto
                   {:tag :rakennuksenTiedot
                    :child [{:tag :rakennustunnus :child [{:tag :jarjestysnumero}
                                                          {:tag :kiinttun}]}
                            {:tag :kayttotarkoitus}
                            {:tag :tilavuus}
                            {:tag :kokonaisala}
                            {:tag :kellarinpinta-ala}
                            {:tag :BIM :child []}
                            {:tag :kerrosluku}
                            {:tag :kerrosala}
                            {:tag :rakentamistapa}
                            {:tag :kantavaRakennusaine :child [{:tag :muuRakennusaine}
                                                               {:tag :rakennusaine}]}
                            {:tag :julkisivu
                             :child [{:tag :muuMateriaali}
                                     {:tag :julkisivumateriaali}]}
                            {:tag :verkostoliittymat :child [{:tag :viemariKytkin}
                                                             {:tag :vesijohtoKytkin}
                                                             {:tag :sahkoKytkin}
                                                             {:tag :maakaasuKytkin}
                                                             {:tag :kaapeliKytkin}]}
                            {:tag :energialuokka}
                            {:tag :energiatehokkuusluku}
                            {:tag :energiatehokkuusluvunYksikko}
                            {:tag :paloluokka}
                                 {:tag :lammitystapa}
                                 {:tag :lammonlahde :child [{:tag :polttoaine}
                                                            {:tag :muu}]}
                                 {:tag :varusteet
                                  :child [{:tag :sahkoKytkin}
                                          {:tag :kaasuKytkin}
                                          {:tag :viemariKytkin}
                                          {:tag :vesijohtoKytkin}
                                          {:tag :lamminvesiKytkin}
                                          {:tag :aurinkopaneeliKytkin}
                                          {:tag :hissiKytkin}
                                          {:tag :koneellinenilmastointiKytkin}
                                          {:tag :saunoja}
                                          {:tag :uima-altaita}
                                          {:tag :vaestonsuoja}]}
                                 {:tag :jaahdytysmuoto}
                                 {:tag :asuinhuoneistot :child [huoneisto]}
                                 ]}
                   {:tag :rakentajatyyppi}
                   {:tag :omistajatieto
                    :child [{:tag :Omistaja
                             :child [{:tag :kuntaRooliKoodi :ns "yht"}
                                     {:tag :VRKrooliKoodi :ns "yht"}
                                     mapping-common/henkilo
                                     mapping-common/yritys
                                     {:tag :omistajalaji :ns "rakval"
                                      :child [{:tag :muu}
                                              {:tag :omistajalaji}]}]}]}])

(def rakennus {:tag :Rakennus
               :child yht-rakennus})

(def lausunto {:tag :Lausunto
               :child [{:tag :viranomainen :ns "yht"}
                       {:tag :pyyntoPvm :ns "yht"}
                       {:tag :lausuntotieto :ns "yht"
                        :child [{:tag :Lausunto
                                 :child [{:tag :viranomainen}
                                         {:tag :lausunto}
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
                                         {:tag :lausuntoPvm}
                                         {:tag :puoltotieto
                                          :child [{:tag :Puolto
                                                   :child [{:tag :puolto}]}]}]}]}]})

(def rakennuslupa_to_krysp
  {:tag :Rakennusvalvonta
   :ns "rakval"
   :attr {:xsi:schemaLocation "http://www.paikkatietopalvelu.fi/gml/yhteiset
                               http://www.paikkatietopalvelu.fi/gml/yhteiset/2.0.9/yhteiset.xsd
                               http://www.paikkatietopalvelu.fi/gml/rakennusvalvonta
                               http://www.paikkatietopalvelu.fi/gml/rakennusvalvonta/2.1.2/rakennusvalvonta.xsd"
          :xmlns:rakval "http://www.paikkatietopalvelu.fi/gml/rakennusvalvonta"
          :xmlns:yht "http://www.paikkatietopalvelu.fi/gml/yhteiset"
          :xmlns:xlink "http://www.w3.org/1999/xlink"
          :xmlns:xsi "http://www.w3.org/2001/XMLSchema-instance"}
   :child [{:tag :toimituksenTiedot :child mapping-common/toimituksenTiedot}
           {:tag :rakennusvalvontaAsiatieto
            :child [{:tag :RakennusvalvontaAsia
                     :child [{:tag :kasittelynTilatieto :child [mapping-common/tilamuutos]}
                             {:tag :luvanTunnisteTiedot
                              :child [mapping-common/lupatunnus]}
                             {:tag :osapuolettieto
                              :child [mapping-common/osapuolet]}
                             {:tag :rakennuspaikkatieto
                              :child [mapping-common/rakennuspaikka]}
                             {:tag :toimenpidetieto
                              :child [{:tag :Toimenpide
                                       :child [{:tag :uusi :child [{:tag :kuvaus}]}
                                               {:tag :laajennus :child [{:tag :laajennuksentiedot
                                                                         :child [{:tag :tilavuus}
                                                                                 {:tag :kerrosala}
                                                                                 {:tag :kokonaisala}
                                                                                 {:tag :huoneistoala
                                                                                  :child [{:tag :pintaAla :ns "yht"}
                                                                                         {:tag :kayttotarkoitusKoodi :ns "yht"}]}]}
                                                                        {:tag :kuvaus}
                                                                        {:tag :perusparannusKytkin}]}
                                               {:tag :perusparannus}
                                               {:tag :uudelleenrakentaminen}
                                               {:tag :purkaminen :child [{:tag :kuvaus}
                                                                        {:tag :purkamisenSyy}
                                                                        {:tag :poistumaPvm }]}
                                               {:tag :muuMuutosTyo :child [{:tag :muutostyonLaji}
                                                                           {:tag :kuvaus}
                                                                           {:tag :perusparannusKytkin}]}
                                               {:tag :kaupunkikuvaToimenpide :child [{:tag :kuvaus}]}
                                               {:tag :rakennustieto
                                                :child [rakennus]}
                                               {:tag :rakennelmatieto
                                                :child [{:tag :Rakennelma :child [{:tag :yksilointitieto :ns "yht"}
                                                                                  {:tag :alkuHetki :ns "yht"}
                                                                                  mapping-common/sijantitieto
                                                                                  {:tag :kuvaus :child [{:tag :kuvaus}]}
                                                                                  {:tag :kokonaisala}]}]}]}]}
                             {:tag :lausuntotieto :child [lausunto]}
                             {:tag :lisatiedot
                              :child [{:tag :Lisatiedot
                                       :child [{:tag :salassapitotietoKytkin}
                                      {:tag :asioimiskieli}
                                      {:tag :suoramarkkinointikieltoKytkin}]}]}
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
                             {:tag :kayttotapaus}
                             {:tag :asianTiedot
                              :child [{:tag :Asiantiedot
                                       :child [{:tag :vahainenPoikkeaminen}
                                                {:tag :rakennusvalvontaasianKuvaus}]}]}]}]}]})

;;
;; *** TODO: Naita common fileen? ***
;;

(defn- get-Liite [title link attachment type file-id]
   {:kuvaus title
    :linkkiliitteeseen link
    :muokkausHetki (to-xml-datetime (:modified attachment))
    :versionumero 1
    :tyyppi type
    :fileId file-id})  ;;TODO: Kysy Terolta mika tama on

(defn- get-liite-for-lausunto [attachment application begin-of-link]
  (let [type "Lausunto"
        title (str (:title application) ": " type "-" (:id attachment))
        file-id (get-in attachment [:latestVersion :fileId])
        attachment-file-name (mapping-common/get-file-name-on-server file-id (get-in attachment [:latestVersion :filename]))
        link (str begin-of-link attachment-file-name)]
    {:Liite (get-Liite title link attachment type file-id)}))

(defn- get-statement-attachments-as-canonical [application begin-of-link ]
  (let [statement-attachments-by-id (group-by
                                      (fn-> :target :id keyword)
                                      (filter
                                        (fn-> :target :type (= "statement"))
                                        (:attachments application)))
        canonical-attachments (for [[id attacments] statement-attachments-by-id]
                                {id (for [attachment attacments]
                                      (get-liite-for-lausunto attachment application begin-of-link))})]
    (not-empty canonical-attachments)))

(defn- get-attachments-as-canonical [application begin-of-link ]
  (let [attachments (:attachments application)
        canonical-attachments (for [attachment attachments
                                    :when (and (:latestVersion attachment) (not (= "statement" (-> attachment :target :type))))
                                    :let [type (get-in attachment [:type :type-id] )
                                          title (str (:title application) ": " type "-" (:id attachment))
                                          file-id (get-in attachment [:latestVersion :fileId])
                                          attachment-file-name (mapping-common/get-file-name-on-server file-id (get-in attachment [:latestVersion :filename]))
                                          link (str begin-of-link attachment-file-name)]]
                                {:Liite (get-Liite title link attachment type file-id)})]
    (not-empty canonical-attachments)))

(defn- write-attachments [attachments output-dir]
  (doseq [attachment attachments]
    (let [file-id (get-in attachment [:Liite :fileId])
          attachment-file (mongo/download file-id)
          content (:content attachment-file)
          attachment-file-name (str output-dir "/" (mapping-common/get-file-name-on-server file-id (:file-name attachment-file)))
          attachment-file (file attachment-file-name)
          ]
      (with-open [out (output-stream attachment-file)
                  in (content)]
        (copy in out)))))

(defn- write-statement-attachments [attachments output-dir]
  (let [f (for [fi attachments]
            (vals fi))
        files (reduce concat (reduce concat f))]
    (write-attachments files output-dir)))

(defn- write-application-pdf-versions [output-dir application submitted-application lang]
  (let [id (:id application)
        submitted-file (file (str output-dir "/" (mapping-common/get-submitted-filename id)))
        current-file (file (str output-dir "/"  (mapping-common/get-current-filename id)))]
    (ke6666/generate submitted-application lang submitted-file)
    (ke6666/generate application lang current-file)))

(defn- add-statement-attachments [canonical statement-attachments]
  (reduce (fn [c a]
            (let [lausuntotieto (get-in c [:Rakennusvalvonta :rakennusvalvontaAsiatieto :RakennusvalvontaAsia :lausuntotieto])
                  lausunto-id (name (first (keys a)))
                  paivitettava-lausunto (some #(if (= (get-in % [:Lausunto :id]) lausunto-id)%) lausuntotieto)
                  index-of-paivitettava (.indexOf lausuntotieto paivitettava-lausunto)
                  paivitetty-lausunto (assoc-in paivitettava-lausunto [:Lausunto :lausuntotieto :Lausunto :liitetieto] ((keyword lausunto-id) a))
                  paivitetty (assoc lausuntotieto index-of-paivitettava paivitetty-lausunto)]
              (assoc-in c [:Rakennusvalvonta :rakennusvalvontaAsiatieto :RakennusvalvontaAsia :lausuntotieto] paivitetty))
            ) canonical statement-attachments))

(defn save-application-as-krysp [application lang submitted-application output-dir begin-of-link]
  (let [file-name  (str output-dir "/" (:id application))
        tempfile   (file (str file-name ".tmp"))
        outfile    (file (str file-name ".xml"))
        canonical-without-attachments  (application-to-canonical application lang)
        statement-attachments (get-statement-attachments-as-canonical application begin-of-link)
        attachments (get-attachments-as-canonical application begin-of-link)
        attachments-with-generated-pdfs (conj attachments
                                          {:Liite
                                           {:kuvaus "Application when submitted"
                                            :linkkiliitteeseen (str begin-of-link (mapping-common/get-submitted-filename (:id application)))
                                            :muokkausHetki (to-xml-datetime (:submitted application))
                                            :versionumero 1
                                            :tyyppi "hakemus_vireilletullessa"}}
                                          {:Liite
                                           {:kuvaus "Application when sent from Lupapiste"
                                            :linkkiliitteeseen (str begin-of-link (mapping-common/get-current-filename (:id application)))
                                            :muokkausHetki (to-xml-datetime (core/now))
                                            :versionumero 1
                                            :tyyppi "hakemus_taustajarjestelmaan_siirrettaessa"}})
        canonical-with-statement-attachments  (add-statement-attachments canonical-without-attachments statement-attachments)
        canonical (assoc-in
                    canonical-with-statement-attachments
                    [:Rakennusvalvonta :rakennusvalvontaAsiatieto :RakennusvalvontaAsia :liitetieto]
                    attachments-with-generated-pdfs)
        xml (element-to-xml canonical rakennuslupa_to_krysp)
        xml-s (indent-str xml)]
    ;(clojure.pprint/pprint (:attachments application))
    ;(clojure.pprint/pprint canonical-with-statement-attachments)
    ;(println xml-s)
    (validate xml-s)
    (fs/mkdirs output-dir)  ;; this has to be called before calling with-open below
    (with-open [out-file-stream (writer tempfile)]
      (emit xml out-file-stream))
    (write-attachments attachments output-dir)
    (write-statement-attachments statement-attachments output-dir)

    (write-application-pdf-versions output-dir application submitted-application lang)
    (when (fs/exists? outfile) (fs/delete outfile))
    (fs/rename tempfile outfile)))
