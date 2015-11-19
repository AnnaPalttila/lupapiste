(ns lupapalvelu.application-bulletins-api
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info warn error errorf fatal]]
            [monger.operators :refer :all]
            [monger.query :as query]
            [noir.response :as resp]
            [sade.core :refer :all]
            [slingshot.slingshot :refer [try+]]
            [sade.strings :as ss]
            [sade.property :as p]
            [lupapalvelu.action :refer [defquery defcommand defraw] :as action]
            [lupapalvelu.application-bulletins :as bulletins]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.document.schemas :as schemas]
            [monger.operators :refer :all]
            [lupapalvelu.states :as states]
            [lupapalvelu.application-search :refer [make-text-query dir]]))

(def bulletin-page-size 10)

(defn- make-query [search-text municipality state]
  (let [text-query         (when-not (ss/blank? search-text)
                             (make-text-query (ss/trim search-text)))
        municipality-query (when-not (ss/blank? municipality)
                             {:versions.municipality municipality})
        state-query        (when-not (ss/blank? state)
                             {:versions.bulletinState state})
        queries            (filter seq [text-query municipality-query state-query])]
    (when-let [and-query (seq queries)]
      {$and and-query})))

(defn- get-application-bulletins-left [page searchText municipality state _]
  (let [query (make-query searchText municipality state)]
    (- (mongo/count :application-bulletins query)
       (* page bulletin-page-size))))

(def- sort-field-mapping {"bulletinState" :bulletinState
                          "municipality" :municipality
                          "address" :address
                          "applicant" :applicant
                          "modified" :modified})

(defn- make-sort [{:keys [field asc]}]
  (let [sort-field (sort-field-mapping field)]
    (cond
      (nil? sort-field) {}
      (sequential? sort-field) (apply array-map (interleave sort-field (repeat (dir asc))))
      :else (array-map sort-field (dir asc)))))

(defn- get-application-bulletins [page searchText municipality state sort]
  (let [query (or (make-query searchText municipality state) {})
        apps (mongo/with-collection "application-bulletins"
               (query/find query)
               (query/fields bulletins/bulletins-fields)
               (query/sort (make-sort sort))
               (query/paginate :page page :per-page bulletin-page-size))]
    (map
      #(assoc (first (:versions %)) :id (:_id %))
      apps)))

(defquery application-bulletins
  {:description "Query for Julkipano"
   :feature :publish-bulletin
   :parameters [page searchText municipality state sort]
   :input-validators [(partial action/number-parameters [:page])]
   :user-roles #{:anonymous}}
  [_]
  (let [parameters [page searchText municipality state sort]]
    (ok :data (apply get-application-bulletins parameters)
        :left (apply get-application-bulletins-left parameters))))

(defquery application-bulletin-municipalities
  {:description "List of distinct municipalities of application bulletins"
   :feature :publish-bulletin
   :parameters []
   :user-roles #{:anonymous}}
  [_]
  (let [municipalities (mongo/distinct :application-bulletins :versions.municipality)]
    (ok :municipalities municipalities)))

(defquery application-bulletin-states
  {:description "List of distinct municipalities of application bulletins"
   :feature :publish-bulletin
   :parameters []
   :user-roles #{:anonymous}}
  [_]
  (let [states (mongo/distinct :application-bulletins :versions.bulletinState)]
    (ok :states states)))

(defn- bulletin-exists! [bulletin-id]
  (let [bulletin (mongo/by-id :application-bulletins bulletin-id)]
    (when-not bulletin
      (fail! :error.invalid-bulletin-id))
    bulletin))

(defn- bulletin-version-is-latest! [bulletin bulletin-version-id]
  (let [latest-version-id (:id (last (:versions bulletin)))]
    (when-not (= bulletin-version-id latest-version-id)
      (fail! :error.invalid-version-id))))

(defn- comment-can-be-added! [bulletin-id bulletin-version-id comment]
  (when (ss/blank? comment)
    (fail! :error.empty-comment))
  (let [bulletin (bulletin-exists! bulletin-id)]
    (when-not (= (:bulletinState bulletin) "proclaimed")
      (fail! :error.invalid-bulletin-state))
    (bulletin-version-is-latest! bulletin bulletin-version-id)))

(def delivery-address-fields #{:firstName :lastName :street :zip :city})

;; TODO user-roles Vetuma autheticated person
(defraw add-bulletin-comment
  {:description "Add comment to bulletin"
   :feature     :publish-bulletin
   :user-roles  #{:anonymous}}
  [{{files :files bulletin-id :bulletin-id comment :bulletin-comment-field bulletin-version-id :bulletin-version-id
     email :email emailPreferred :emailPreferred otherReceiver :otherReceiver :as data} :data created :created :as action}]
  (try+
    (comment-can-be-added! bulletin-id bulletin-version-id comment)
    (let [address-source   (if otherReceiver data (get-in (lupapalvelu.vetuma/vetuma-session) [:user]))
          delivery-address (select-keys address-source delivery-address-fields)
          contact-info     (merge delivery-address {:email email
                                                    :emailPreferred (= emailPreferred "on")})
          comment      (bulletins/create-comment comment contact-info created)
          stored-files (bulletins/store-files bulletin-id (:id comment) files)]
      (mongo/update-by-id :application-bulletins bulletin-id {$push {(str "comments." bulletin-version-id) (assoc comment :attachments stored-files)}})
      (->> {:ok true}
           (resp/json)
           (resp/content-type "application/json")
           (resp/status 200)))
    (catch [:sade.core/type :sade.core/fail] {:keys [text] :as all}
      (->> {:ok false :text text}
           (resp/json)
           (resp/content-type "application/json")
           (resp/status 200)))
    (catch Throwable t
      (error "Failed to store bulletin comment" t)
      (resp/status 400 :error.storing-bulletin-command-failed))))

(defn- get-search-fields [fields app]
  (into {} (map #(hash-map % (% app)) fields)))

(defn- create-bulletin [application created & [updates]]
  (let [app-snapshot (bulletins/create-bulletin-snapshot application)
        app-snapshot (if updates
                       (merge app-snapshot updates)
                       app-snapshot)
        search-fields [:municipality :address :verdicts :_applicantIndex :bulletinState :applicant]
        search-updates (get-search-fields search-fields app-snapshot)]
    (bulletins/snapshot-updates app-snapshot search-updates created)))

(defcommand publish-bulletin
  {:parameters [id]
   :feature :publish-bulletin
   :user-roles #{:authority}
   :states     (states/all-application-states-but :draft :open :submitted)}
  [{:keys [application created] :as command}]
  (mongo/update-by-id :application-bulletins id (create-bulletin application created) :upsert true)
  (ok))

(defcommand move-to-proclaimed
  {:parameters [id proclamationEndsAt proclamationStartsAt proclamationText]
   :feature :publish-bulletin
   :user-roles #{:authority}
   :states     #{:sent :complementNeeded}}
  [{:keys [application created] :as command}]
  (let [updates (->> (create-bulletin application created {:proclamationEndsAt proclamationEndsAt
                                                           :proclamationStartsAt proclamationStartsAt
                                                           :proclamationText proclamationText}))]
    (mongo/update-by-id :application-bulletins id updates :upsert true)
    (ok)))

(defcommand move-to-verdict-given
  {:parameters [id verdictGivenAt appealPeriodStartsAt appealPeriodEndsAt verdictGivenText]
   :feature :publish-bulletin
   :user-roles #{:authority}
   :states     #{:verdictGiven}}
  [{:keys [application created] :as command}]
  (let [updates (->> (create-bulletin application created {:verdictGivenAt verdictGivenAt
                                                           :appealPeriodStartsAt appealPeriodStartsAt
                                                           :appealPeriodEndsAt appealPeriodEndsAt
                                                           :verdictGivenText verdictGivenText}))]
    (mongo/update-by-id :application-bulletins id updates :upsert true)
    (ok)))

(defcommand move-to-final
  {:parameters [id officialAt]
   :feature :publish-bulletin
   :user-roles #{:authority}
   :states     #{:verdictGiven}}
  [{:keys [application created] :as command}]
  ; Note there is currently no way to move application to final state so we sent bulletin state manuall
  (let [updates (->> (create-bulletin application created {:officialAt officialAt
                                                           :bulletinState :final}))]
    (clojure.pprint/pprint updates)
    (mongo/update-by-id :application-bulletins id updates :upsert true)
    (ok)))

(defquery bulletin
  {:parameters [bulletinId]
   :feature :publish-bulletin
   :user-roles #{:anonymous}}
  "return only latest version for application bulletin"
  (if-let [bulletin (bulletins/get-bulletin bulletinId)]
    (let [latest-version (-> bulletin :versions first)
          bulletin-version (assoc latest-version :versionId (:id latest-version)
                                                 :id (:id bulletin))
          append-schema-fn (fn [{schema-info :schema-info :as doc}]
                             (assoc doc :schema (schemas/get-schema schema-info)))
          bulletin (-> bulletin-version
                       (update-in [:documents] (partial map append-schema-fn))
                       (assoc :stateSeq bulletins/bulletin-state-seq))]
      (ok :bulletin bulletin))
    (fail :error.bulletin.not-found)))

(defquery bulletin-versions
  "returns all bulletin versions for application bulletin with comments"
  {:parameters [bulletinId]
   :feature    :publish-bulletin
   :user-roles #{:authority :applicant}}
  (let [bulletin-fields (-> bulletins/bulletins-fields
                            (dissoc :versions)
                            (merge {:comments 1
                                    :bulletinState 1}))
        bulletin (mongo/with-id (mongo/by-id :application-bulletins bulletinId bulletin-fields))]
    (ok :bulletin bulletin)))
