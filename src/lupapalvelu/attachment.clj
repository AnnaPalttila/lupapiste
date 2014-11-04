(ns lupapalvelu.attachment
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn warnf error errorf fatal]]
            [monger.operators :refer :all]
            [sade.util :as util]
            [sade.env :as env]
            [sade.strings :as ss]
            [lupapalvelu.core :refer [fail fail!]]
            [lupapalvelu.action :refer [update-application application->command]]
            [lupapalvelu.domain :refer [get-application-as get-application-no-access-checking]]
            [lupapalvelu.comment :as comment]
            [lupapalvelu.mongo :refer [$each] :as mongo]
            [lupapalvelu.user :as user]
            [lupapalvelu.mime :as mime]))

;;
;; Metadata
;;

(def attachment-types-osapuoli
  [:cv
   :patevyystodistus
   :paa_ja_rakennussuunnittelijan_tiedot
   :tutkintotodistus])

(def attachment-meta-types [:size :scale :op :contents])

(def attachment-scales
  [:1:20
   :1:50
   :1:100
   :1:200
   :1:500
   :muu])

(def attachment-sizes
  [:A0
   :A1
   :A2
   :A3
   :A4
   :A5
   :B0
   :B1
   :B2
   :B3
   :B4
   :B5
   :muu])

(def ^:private attachment-types-R
  [:hakija [:osakeyhtion_perustamiskirja
            :ote_asunto_osakeyhtion_hallituksen_kokouksen_poytakirjasta
            :ote_kauppa_ja_yhdistysrekisterista
            :valtakirja]
   :rakennuspaikan_hallinta [:jaljennos_kauppakirjasta_tai_muusta_luovutuskirjasta
                             :jaljennos_myonnetyista_lainhuudoista
                             :jaljennos_perunkirjasta
                             :jaljennos_vuokrasopimuksesta
                             :ote_asunto-osakeyhtion_kokouksen_poytakirjasta
                             :rasitesopimus
                             :rasitustodistus
                             :todistus_erityisoikeuden_kirjaamisesta]
   :rakennuspaikka [:kiinteiston_vesi_ja_viemarilaitteiston_suunnitelma
                    :ote_alueen_peruskartasta
                    :ote_asemakaavasta_jos_asemakaava_alueella
                    :ote_kiinteistorekisteristerista
                    :ote_ranta-asemakaavasta
                    :ote_yleiskaavasta
                    :rakennusoikeuslaskelma
                    :selvitys_rakennuspaikan_perustamis_ja_pohjaolosuhteista
                    :tonttikartta_tarvittaessa]
   :paapiirustus [:asemapiirros
                  :pohjapiirros
                  :leikkauspiirros
                  :julkisivupiirros]
   :ennakkoluvat_ja_lausunnot [:elyn_tai_kunnan_poikkeamapaatos
                               :naapurien_suostumukset
                               :selvitys_naapurien_kuulemisesta
                               :suunnittelutarveratkaisu
                               :ymparistolupa]

   :rakentamisen_aikaiset [:erityissuunnitelma]
   :osapuolet attachment-types-osapuoli
   :muut [:energiataloudellinen_selvitys
          :ilmanvaihtosuunnitelma
          :ilmoitus_vaestonsuojasta
          :jatevesijarjestelman_rakennustapaseloste
          :julkisivujen_varityssuunnitelma
          :kalliorakentamistekninen_suunnitelma
          :katselmuksen_tai_tarkastuksen_poytakirja
          :kerrosalaselvitys
          :liikkumis_ja_esteettomyysselvitys
          :lomarakennuksen_muutos_asuinrakennukseksi_selvitys_maaraysten_toteutumisesta
          :lammityslaitesuunnitelma
          :merkki_ja_turvavalaistussuunnitelma
          :palotekninen_selvitys
          :paloturvallisuusselvitys
          :paloturvallisuussuunnitelma
          :piha_tai_istutussuunnitelma
          :pohjaveden_hallintasuunnitelma
          :radontekninen_suunnitelma
          :rakennesuunnitelma
          :rakennetapaselvitys
          :rakennukseen_tai_sen_osaan_kohdistuva_kuntotutkimus_jos_korjaus_tai_muutostyo
          :rakennuksen_tietomalli_BIM
          :rakennusautomaatiosuunnitelma
          :riskianalyysi
          :sammutusautomatiikkasuunnitelma
          :selvitys_kiinteiston_jatehuollon_jarjestamisesta
          :selvitys_liittymisesta_ymparoivaan_rakennuskantaan
          :selvitys_purettavasta_rakennusmateriaalista_ja_hyvaksikaytosta
          :selvitys_rakennuksen_aaniteknisesta_toimivuudesta
          :selvitys_rakennuksen_kosteusteknisesta_toimivuudesta
          :selvitys_rakennuksen_rakennustaiteellisesta_ja_kulttuurihistoriallisesta_arvosta_jos_korjaus_tai_muutostyo
          :selvitys_rakennusjatteen_maarasta_laadusta_ja_lajittelusta
          :selvitys_rakennuspaikan_korkeusasemasta
          :selvitys_rakennuspaikan_terveellisyydesta
          :selvitys_rakenteiden_kokonaisvakavuudesta_ja_lujuudesta
          :selvitys_sisailmastotavoitteista_ja_niihin_vaikuttavista_tekijoista
          :selvitys_tontin_tai_rakennuspaikan_pintavesien_kasittelysta
          :sopimusjaljennos
          :suunnitelma_paloilmoitinjarjestelmista_ja_koneellisesta_savunpoistosta
          :vaestonsuojasuunnitelma
          :valaistussuunnitelma
          :valokuva
          :vesi_ja_viemariliitoslausunto_tai_kartta
          :vesikattopiirustus
          :ympariston_tietomalli_BIM
          :muu]])

(def ^:private attachment-types-YA
  [:yleiset-alueet [:aiemmin-hankittu-sijoituspaatos
                    :asemapiirros
                    :liitoslausunto
                    :poikkileikkaus
                    :rakennuspiirros
                    :suunnitelmakartta
                    :tieto-kaivupaikkaan-liittyvista-johtotiedoista
                    :tilapainen-liikennejarjestelysuunnitelma
                    :tyyppiratkaisu
                    :tyoalueen-kuvaus
                    :valokuva
                    :valtakirja]
   :osapuolet attachment-types-osapuoli
   ;; This is needed for statement attachments to work.
   :muut [:muu]])

(def ^:private attachment-types-YI
  [:kartat [:kartta-melun-ja-tarinan-leviamisesta]
   :muut [:muu]])

(def ^:private attachment-types-YL
   [:laitoksen_tiedot [:voimassa_olevat_ymparistolupa_vesilupa
                       :muut_paatokset_sopimukset
                       :selvitys_ymparistovahinkovakuutuksesta]
    :laitosalue_sen_ymparisto [:tiedot_kiinteistoista
                               :tiedot_toiminnan_sijaintipaikasta
                               :kaavaote
                               :selvitys_pohjavesialueesta
                               :selvitys_rajanaapureista
                               :ote_asemakaavasta
                               :ote_yleiskaavasta]
    :laitoksen_toiminta [:yleiskuvaus_toiminnasta
                         :yleisolle_tarkoitettu_tiivistelma
                         :selvitys_tuotannosta
                         :tiedot_toiminnan_suunnitellusta
                         :tiedot_raaka-aineista
                         :tiedot_energian
                         :energiansaastosopimus
                         :vedenhankinta_viemarointi
                         :arvio_ymparistoriskeista
                         :liikenne_liikennejarjestelyt
                         :selvitys_ymparistoasioiden_hallintajarjestelmasta]
    :ymparistokuormitus [:paastot_vesistoon_viemariin
                         :paastot_ilmaan
                         :paastot_maaperaan_pohjaveteen
                         :tiedot_pilaantuneesta_maaperasta
                         :melupaastot_tarina
                         :selvitys_paastojen_vahentamisesta_puhdistamisesta
                         :syntyvat_jatteet
                         :selvitys_jatteiden_maaran_haitallisuuden_vahentamiseksi
                         :kaatopaikkaa_koskevan_lupahakemuksen_lisatiedot
                         :selvitys_vakavaraisuudesta_vakuudesta
                         :jatteen_hyodyntamista_kasittelya_koskevan_toiminnan_lisatiedot]
    :paras_tekniikka_kaytanto [:arvio_tekniikan_soveltamisesta
                               :arvio_paastojen_vahentamistoimien_ristikkaisvaikutuksista
                               :arvio_kaytannon_soveltamisesta]
    :vaikutukset_ymparistoon [:arvio_vaikutuksista_yleiseen_viihtyvyyteen_ihmisten_terveyteen
                              :arvio_vaikutuksista_luontoon_luonnonsuojeluarvoihin_rakennettuun_ymparistoon
                              :arvio_vaikutuksista_vesistoon_sen_kayttoon
                              :arvio_ilmaan_joutuvien_paastojen_vaikutuksista
                              :arvio_vaikutuksista_maaperaan_pohjaveteen
                              :arvio_melun_tarinan_vaikutuksista
                              :arvio_ymparistovaikutuksista]
    :tarkkailu_raportointi [:kayttotarkkailu
                            :paastotarkkailu
                            :vaikutustarkkailu
                            :mittausmenetelmat_laitteet_laskentamenetelmat_laadunvarmistus
                            :raportointi_tarkkailuohjelmat]
    :vahinkoarvio_estavat_toimenpiteet [:toimenpiteet_vesistoon_kohdistuvien_vahinkojen_ehkaisemiseksi
                                        :korvausestiys_vesistoon_kohdistuvista_vahingoista
                                        :toimenpiteet_muiden_kuin_vesistovahinkojen_ehkaisemiseksi]
    :muut [:asemapiirros_prosessien_paastolahteiden_sijainti
           :ote_alueen_peruskartasta_sijainti_paastolahteet_olennaiset_kohteet
           :prosessikaavio_yksikkoprosessit_paastolahteet
           :selvitys_suuronnettomuuden_vaaran_arvioimiseksi
           :muu]])

(def ^:private attachment-types-MAL
  [:hakija [:valtakirja
            :ottamisalueen_omistus_hallintaoikeus]
   :ottamisalue [:ote_alueen_peruskartasta
                 :ote_yleiskaavasta
                 :ote_asemakaavasta
                 :naapurit]
   :erityissuunnitelmat [:yvalain_mukainen_arviointiselostus
                         :luonnonsuojelulain_arviointi
                         :kivenmurskaamo
                         :selvitys_jalkihoitotoimenpiteista
                         :ottamissuunnitelma
                         :kaivannaisjatteen_jatehuoltosuunnitelma]
   :muut [:vakuus_ottamisen_aloittamiseksi_ennen_luvan_lainvoimaa
          :selvitys_tieyhteyksista_oikeuksista
          :pohjavesitutkimus
          :muu]])

(def ^:private attachment-types-KM
  [:hakija [:valtakirja
            :virkatodistus
            :ote_kauppa_ja_yhdistysrekisterista]
   :kiinteiston_hallinta [:jaljennos_perunkirjasta
                          :rasitesopimus
                          :rasitesopimuksen_liitekartta
                          :ote_asunto-osakeyhtion_kokouksen_poytakirjasta]
   :muut [:muu]])

;;
;; Api
;;

(defn by-file-ids [file-ids attachment]
  (let [file-id-set (set file-ids)
        attachment-file-ids (map :fileId (:versions attachment))]
    (some #(file-id-set %) attachment-file-ids)))

(defn get-attachments-infos
  "gets attachments from application"
  [application attachment-ids]
  (let [ids (set attachment-ids)] (filter (comp ids :id) (:attachments application))))

(defn get-attachment-info
  "gets an attachment from application or nil"
  [application attachment-id]
  (first (get-attachments-infos application [attachment-id])))

(defn create-sent-timestamp-update-statements [attachments file-ids timestamp]
  (mongo/generate-array-updates :attachments attachments (partial by-file-ids file-ids) :sent timestamp))

(defn get-attachment-types-by-permit-type
  "Returns partitioned list of allowed attachment types or throws exception"
  [permit-type]
  {:pre [permit-type]}
  (partition 2
    (case (keyword permit-type)
      :R  attachment-types-R
      :YA attachment-types-YA
      :P  attachment-types-R
      :YI attachment-types-YI
      :YL attachment-types-YL
      :VVVL attachment-types-YI ;TODO quick fix to get test and qa work. Put correct attachment list here
      :MAL attachment-types-MAL
      :KM attachment-types-KM
      (fail! (str "unsupported permit-type: " permit-type)))))

(defn get-attachment-types-for-application
  [application]
  {:pre [application]}
  (get-attachment-types-by-permit-type (:permitType application)))

(defn make-attachment [now target locked applicationState op attachement-type & [attachment-id]]
  {:id (or attachment-id (mongo/create-id))
   :type attachement-type
   :modified now
   :locked locked
   :applicationState applicationState
   :state :requires_user_action
   :target target
   :op op
   :signatures []
   :versions []})

(defn make-attachments
  "creates attachments with nil target"
  [now applicationState attachement-types]
  (map (partial make-attachment now nil false applicationState nil) attachement-types))

(defn create-attachment [application attachement-type now target locked & [attachment-id]]
  {:pre [(map? application)]}
  (let [attachment (make-attachment now target locked (:state application) nil attachement-type attachment-id)]
    (update-application
      (application->command application)
      {$set {:modified now}
       $push {:attachments attachment}})

    (:id attachment)))

(defn create-attachments [application attachement-types now]
  {:pre [(map? application)]}
  (let [attachments (make-attachments now (:state application) attachement-types)]
    (update-application
      (application->command application)
      {$set {:modified now}
       $push {:attachments {$each attachments}}})
    (map :id attachments)))

(defn- next-attachment-version [{major :major minor :minor} user]
  (let [major (or major 0)
        minor (or minor 0)]
    (if (user/authority? user)
      {:major major, :minor (inc minor)}
      {:major (inc major), :minor 0})))

(defn attachment-latest-version [attachments attachment-id]
  (:version (:latestVersion (some #(when (= attachment-id (:id %)) %) attachments))))

(defn version-number
  [{{:keys [major minor]} :version}]
  (+ (* 1000 major) minor))

(defn latest-version-after-removing-file [attachments attachment-id fileId]
  (let [attachment (some #(when (= attachment-id (:id %)) %) attachments)
        versions   (:versions attachment)
        stripped   (filter #(not= (:fileId %) fileId) versions)
        sorted     (sort-by version-number stripped)
        latest     (last sorted)]
    latest))

(defn set-attachment-version
  ([options]
    {:pre [(map? options)]}
    (set-attachment-version options 5))
  ([{:keys [application attachment-id file-id filename content-type size comment-text now user stamped make-comment state target]
     :or {make-comment true state :requires_authority_action} :as options}
    retry-limit]
    {:pre [(map? options) (map? application) (string? attachment-id) (string? file-id) (string? filename) (string? content-type) (number? size) (number? now) (map? user) (not (nil? stamped))]}
    ; TODO refactor to use proper optimistic locking
    ; TODO refactor to return version-model and mongo updates, so that updates can be merged into single statement
    (if (pos? retry-limit)
      (let [latest-version (attachment-latest-version (application :attachments) attachment-id)
            next-version (next-attachment-version latest-version user)
            version-model {:version  next-version
                           :fileId   file-id
                           :created  now
                           :accepted nil
                           :user    (user/summary user)
                           ; File name will be presented in ASCII when the file is downloaded.
                           ; Conversion could be done here as well, but we don't want to lose information.
                           :filename filename
                           :contentType content-type
                           :size size
                           :stamped stamped}

            comment-target {:type :attachment
                            :id attachment-id
                            :version next-version
                            :filename filename
                            :fileId file-id}

            result-count (update-application
                           (application->command application)
                           {:attachments {$elemMatch {:id attachment-id
                                                      :latestVersion.version.major (:major latest-version)
                                                      :latestVersion.version.minor (:minor latest-version)}}}
                           (util/deep-merge
                             (when make-comment (comment/comment-mongo-update (:state application) comment-text comment-target :system false user nil now))
                             (when target {$set {:attachments.$.target target}})
                             {$set {:modified now
                                    :attachments.$.modified now
                                    :attachments.$.state  state
                                    :attachments.$.latestVersion version-model}
                              $push {:attachments.$.versions version-model}})
                           true)]
        ; Check return value and try again with new version number
        (if (pos? result-count)
          (assoc version-model :id attachment-id)
          (do
            (errorf
              "Latest version of attachment %s changed before new version could be saved, retry %d time(s)."
              attachment-id retry-limit)
            (set-attachment-version (assoc options :application (mongo/by-id :applications (:id application))) (dec retry-limit)))))
      (do
        (error "Concurrency issue: Could not save attachment version meta data.")
        nil))))

(defn update-latest-version-content [application attachment-id file-id size now]
  (let [attachment (get-attachment-info application attachment-id)
        latest-version-index (-> attachment :versions count dec)
        latest-version-path (str "attachments.$.versions." latest-version-index ".")
        old-file-id (get-in attachment [:latestVersion :fileId])]

    (when-not (= old-file-id file-id)
      (mongo/delete-file-by-id old-file-id))

    (update-application
      (application->command application)
      {:attachments {$elemMatch {:id attachment-id}}}
      {$set {:modified now
             :attachments.$.modified now
             (str latest-version-path "fileId") file-id
             (str latest-version-path "size") size
             (str latest-version-path "created") now
             :attachments.$.latestVersion.fileId file-id
             :attachments.$.latestVersion.size size
             :attachments.$.latestVersion.created now}})))

(defn update-or-create-attachment
  "If the attachment-id matches any old attachment, a new version will be added.
   Otherwise a new attachment is created."
  [{:keys [application attachment-id attachment-type file-id filename content-type size comment-text created user target locked] :as options}]
  {:pre [(map? application)]}
  (let [att-id (cond
                 (ss/blank? attachment-id) (create-attachment application attachment-type created target locked)
                 (pos? (mongo/count :applications {:_id (:id application) :attachments.id attachment-id})) attachment-id
                 :else (create-attachment application attachment-type created target locked attachment-id))]
    (set-attachment-version (assoc options :attachment-id att-id :now created :stamped false))))

(defn parse-attachment-type [attachment-type]
  (if-let [match (re-find #"(.+)\.(.+)" (or attachment-type ""))]
    (let [[type-group type-id] (->> match (drop 1) (map keyword))]
      {:type-group type-group :type-id type-id})))

(defn- allowed-attachment-types-contain? [allowed-types {:keys [type-group type-id]}]
  (let [type-group (keyword type-group)
        type-id (keyword type-id)]
    (if-let [types (some (fn [[group-name group-types]] (if (= (keyword group-name) type-group) group-types)) allowed-types)]
      (some #(= (keyword %) type-id) types))))

(defn allowed-attachment-type-for-application? [application attachment-type]
  (when application
    (let [allowedAttachmentTypes (get-attachment-types-for-application application)]
     (allowed-attachment-types-contain? allowedAttachmentTypes attachment-type))))

(defn get-attachment-info-by-file-id
  "gets an attachment from application or nil"
  [{:keys [attachments]} file-id]
  (first
    (filter
      (partial by-file-ids #{file-id})
      attachments)))

(defn attachment-file-ids
  "Gets all file-ids from attachment."
  [application attachment-id]
  (->> (get-attachment-info application attachment-id) :versions (map :fileId)))

(defn attachment-latest-file-id
  "Gets latest file-id from attachment."
  [application attachment-id]
  (last (attachment-file-ids application attachment-id)))

(defn file-id-in-application?
  "tests that file-id is referenced from application"
  [application attachment-id file-id]
  (let [file-ids (attachment-file-ids application attachment-id)]
    (boolean (some #{file-id} file-ids))))

(defn delete-attachment
  "Delete attachement with all it's versions. does not delete comments. Non-atomic operation: first deletes files, then updates document."
  [{:keys [attachments] :as application} attachment-id]
  (info "1/3 deleting files of attachment" attachment-id)
  (dorun (map mongo/delete-file-by-id (attachment-file-ids application attachment-id)))
  (info "2/3 deleted files of attachment" attachment-id)
  (update-application (application->command application) {$pull {:attachments {:id attachment-id}}})
  (info "3/3 deleted meta-data of attachment" attachment-id))

(defn delete-attachment-version
  "Delete attachment version. Is not atomic: first deletes file, then removes application reference."
  [{:keys [id attachments] :as application} attachment-id fileId]
  (let [latest-version (latest-version-after-removing-file attachments attachment-id fileId)]
    (infof "1/3 deleting file %s of attachment %s" fileId attachment-id)
    (mongo/delete-file-by-id fileId)
    (infof "2/3 deleted file %s of attachment %s" fileId attachment-id)
    (update-application
      (application->command application)
      {:attachments {$elemMatch {:id attachment-id}}}
      {$pull {:attachments.$.versions {:fileId fileId}}
       $set  {:attachments.$.latestVersion latest-version}})
    (infof "3/3 deleted meta-data of file %s of attachment" fileId attachment-id)))

(defn get-attachment-as
  "Returns the attachment if user has access to application, otherwise nil."
  [user file-id]
  (when-let [attachment (mongo/download file-id)]
    (when-let [application (get-application-as (:application attachment) user)]
      (when (seq application) attachment))))

(defn get-attachment
  "Returns the attachment without access checking, otherwise nil."
  [file-id]
  (when-let [attachment (mongo/download file-id)]
    (when-let [application (get-application-no-access-checking (:application attachment))]
      (when (seq application) attachment))))

(defn output-attachment
  [attachment-id download? attachment-fn]
  (debugf "file download: attachment-id=%s" attachment-id)
  (if-let [attachment (attachment-fn attachment-id)]
    (let [response {:status 200
                    :body ((:content attachment))
                    :headers {"Content-Type" (:content-type attachment)
                              "Content-Length" (str (:content-length attachment))}}]
      (if download?
        (assoc-in response
          [:headers "Content-Disposition"]
          (format "attachment;filename=\"%s\"" (ss/encode-filename (:file-name attachment))))
        response))
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "404"}))

(defn attach-file!
  "Uploads a file to MongoDB and creates a corresponding attachment structure to application.
   Content can be a file or input-stream.
   Returns attachment version."
  [options]
  {:pre [(map? (:application options))]}
  (let [file-id (mongo/create-id)
        {:keys [filename content]} options
        application-id (-> options :application :id)
        sanitazed-filename (mime/sanitize-filename filename)
        content-type (mime/mime-type sanitazed-filename)
        options (merge options {:file-id file-id
                                :filename sanitazed-filename
                                :content-type content-type})]
    (mongo/upload file-id sanitazed-filename content-type content :application application-id)
    (update-or-create-attachment options)))

(defn get-attachments-by-operation
  [{:keys [attachments] :as application} op-id]
  (filter #(= (:id (:op %)) op-id) attachments))

