(ns lupapalvelu.application-meta-fields
  (:require [clojure.string :as s]
            [monger.operators :refer :all]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.neighbors :as neighbors]
            [lupapalvelu.core :refer :all]
;            [lupapalvelu.document.canonical-common :refer [by-type]]
;            [sade.util :refer :all]
            [sade.env :as env]
            [sade.strings :as ss]))

(defn get-applicant-name [_ app]
  (if (:infoRequest app)
    (let [{first-name :firstName last-name :lastName} (first (domain/get-auths-by-role app :owner))]
      (str first-name \space last-name))
    (when-let [body (:data (domain/get-applicant-document app))]
      (if (= (get-in body [:_selected :value]) "yritys")
        (get-in body [:yritys :yritysnimi :value])
        (let [{first-name :etunimi last-name :sukunimi} (get-in body [:henkilo :henkilotiedot])]
          (str (:value first-name) \space (:value last-name)))))))

(defn get-application-operation [app]
  (first (:operations app)))

(defn- count-unseen-comment [user app]
  (let [last-seen (get-in app [:_comments-seen-by (keyword (:id user))] 0)]
    (count (filter (fn [comment]
                     (and (> (:created comment) last-seen)
                          (not= (get-in comment [:user :id]) (:id user))
                          (not (s/blank? (:text comment)))))
                   (:comments app)))))

(defn- count-unseen-statements [user app]
  (if-not (:infoRequest app)
    (let [last-seen (get-in app [:_statements-seen-by (keyword (:id user))] 0)]
      (count (filter (fn [statement]
                       (and (> (or (:given statement) 0) last-seen)
                            (not= (ss/lower-case (get-in statement [:person :email])) (ss/lower-case (:email user)))))
                     (:statements app))))
    0))

(defn- count-unseen-verdicts [user app]
  (if (and (= (:role user) "applicant") (not (:infoRequest app)))
    (let [last-seen (get-in app [:_verdicts-seen-by (keyword (:id user))] 0)]
      (count (filter (fn [verdict] (> (or (:timestamp verdict) 0) last-seen)) (:verdicts app))))
    0))

(defn- count-attachments-requiring-action [user app]
  (if-not (:infoRequest app)
    (let [count-attachments (fn [state] (count (filter #(and (= (:state %) state) (seq (:versions %))) (:attachments app))))]
      (case (keyword (:role user))
        :applicant (count-attachments "requires_user_action")
        :authority (count-attachments "requires_authority_action")
        0))
    0))

(defn- count-document-modifications-per-doc [user app]
  (if (and (env/feature? :docIndicators) (= (:role user) "authority") (not (:infoRequest app)))
    (into {} (map (fn [doc] [(:id doc) (model/modifications-since-approvals doc)]) (:documents app)))
    {}))


(defn- count-document-modifications [user app]
  (if (and (env/feature? :docIndicators) (= (:role user) "authority") (not (:infoRequest app)))
    (reduce + 0 (vals (:documentModificationsPerDoc app)))
    0))

(defn- indicator-sum [_ app]
  (reduce + (map (fn [[k v]] (if (#{:documentModifications :unseenStatements :unseenVerdicts :attachmentsRequiringAction} k) v 0)) app)))

(def meta-fields [{:field :applicant :fn get-applicant-name}
                  {:field :neighbors :fn neighbors/normalize-neighbors}
                  {:field :documentModificationsPerDoc :fn count-document-modifications-per-doc}
                  {:field :documentModifications :fn count-document-modifications}
                  {:field :unseenComments :fn count-unseen-comment}
                  {:field :unseenStatements :fn count-unseen-statements}
                  {:field :unseenVerdicts :fn count-unseen-verdicts}
                  {:field :attachmentsRequiringAction :fn count-attachments-requiring-action}
                  {:field :indicators :fn indicator-sum}])

(defn with-meta-fields [user app]
  (reduce (fn [app {field :field f :fn}] (assoc app field (f user app))) app meta-fields))

;(defn- get-link-permits-operation-from-mongo [link-permit-id]
;  (-> (mongo/by-id "applications" link-permit-id {:operations 1}) :operations first :name))

(defn enrich-with-link-permit-data [app]
  (let [app-id (:id app)
        resp (mongo/select :app-links {:link {$in [app-id]}})]
    (if (seq resp)
      ;; Link permit data was found
      (let [convert-fn (fn [link-data]
                         (let [link-array (:link link-data)
                               app-index (.indexOf link-array app-id)
                               link-permit-id (link-array (if (= 0 app-index) 1 0))
                               link-permit-type (:linkpermittype ((keyword link-permit-id) link-data))]
                           (if (= (:type ((keyword app-id) link-data)) "application")

                             ;; TODO: Jos viiteluvan tyyppi on myös jatkolupa, niin sitten :operation pitaa hakea
                             ;;       viela kauempaa, eli viiteluvan viiteluvalta. Eli looppia tahan?
                             ;;
;                            {:id link-permit-id :type link-permit-type}
                             (let [;link-permit-app-op (get-link-permits-operation-from-mongo link-permit-id)
                                   link-permit-app-op (-> (mongo/by-id "applications" link-permit-id {:operations 1})
                                                        :operations first :name)]
                               {:id link-permit-id :type link-permit-type :operation link-permit-app-op})
                             {:id link-permit-id})))
            our-link-permits (filter #(= (:type ((keyword app-id) %)) "application") resp)
            apps-linking-to-us (filter #(= (:type ((keyword app-id) %)) "linkpermit") resp)]

        (-> app
          (assoc :linkPermitData (if (seq our-link-permits)
                                   (into [] (map convert-fn our-link-permits))
                                   nil))
          (assoc :appsLinkingToUs (if (seq apps-linking-to-us)
                                    (into [] (map convert-fn apps-linking-to-us))
                                    nil))))
      ;; No link permit data found
      (-> app
        (assoc :linkPermitData nil)
        (assoc :appsLinkingToUs nil)))))



;; For Jatkolupa

;;*******************
;; TODO:
;;
;; Avaimien :continuation-period-end-date  ja  :continuation-period-description
;; canonical-testaukseen riittaa se, etta tekee sen jossain ya canonical_testissa, esim kaivuluvalla.
;;
;;*******************

#_(defn doc-from-app-by-name [app name]
  (filter #(= name (-> % :schema-info :name)) (:documents app)))

#_(defn merge-continuation-period-permit-with-orig-application [app]
;  (println "\n merge-continuation-period-permit-with-orig-application, app: ")
;  (clojure.pprint/pprint app)
;  (println "\n linkPermitData count: " (-> app :linkPermitData count))

  (when-not (-> app :linkPermitData first :id) (fail! :error.link-permit-must-be-chosen-first))
  (when (> 1 (-> app :linkPermitData count)) (fail! :error.only-one-link-permit-must-be-chosen))

  (let [orig-app (mongo/by-id "applications" (-> app :linkPermitData first :id))
;        _ (println "\n tyoaika-paattyy-doc: " (doc-from-app-by-name app "tyo-aika-for-jatkoaika"))
        end-date (-> (domain/get-document-by-name app "tyo-aika-for-jatkoaika") :data :tyoaika-paattyy-pvm :value)
;        _ (println "\n tyoaika-paattyy-doc: " (doc-from-app-by-name app "hankkeen-kuvaus-minimum"))
        kuvaus (-> (domain/get-document-by-name app "hankkeen-kuvaus-minimum") :data :kuvaus :value)
        result (-> orig-app
                 (assoc :continuation-period-end-date end-date)
                 (assoc :continuation-period-description kuvaus))]

;    (println "\n merge-continuation-period-permit-with-orig-application, result: ")
;    (clojure.pprint/pprint result)
;    (println "\n")

    result))



#_(defn enrich-with-additional-data-from-link-permit [app]
  (if-let [link-permit-id (-> app :linkPermitData :id)]
    (let [lp (mongo/by-id "applications" link-permit-id {:operations 1})
          documents-by-type (by-type (:documents lp))
          mainostus-viitoitus-tapahtuma-doc (or (-> documents-by-type :mainosten-tai-viitoitusten-sijoittaminen first :data) {})
          mainostus-viitoitus-tapahtuma-name (-> mainostus-viitoitus-tapahtuma-doc :_selected :value)
          mainostus-viitoitus-tapahtuma (mainostus-viitoitus-tapahtuma-doc (keyword mainostus-viitoitus-tapahtuma-name))]
      (-> app
        ;, Operation name
        (assoc-in [:linkPermitData :operation] (-> lp :operations first :name))
        ;; Work start date    *Horrror!*
        (assoc-in [:linkPermitData :work-start-date]
          (or
            (to-xml-date-from-string (-> documents-by-type :tyoaika first :data :tyoaika-alkaa-pvm :value))
            (to-xml-date-from-string (-> mainostus-viitoitus-tapahtuma :tapahtuma-aika-paattyy-pvm :value))
            (to-xml-date (:submitted lp))))))

    (fail! :error.link-permit-must-be-chosen-first)))

