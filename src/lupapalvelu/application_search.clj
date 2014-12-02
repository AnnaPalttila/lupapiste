(ns lupapalvelu.application-search
  (:require [taoensso.timbre :as timbre :refer [debug info warn error]]
            [clojure.string :as s]
            [clojure.set :refer [rename-keys]]
            [monger.operators :refer :all]
            [monger.query :as query]
            [sade.strings :as ss]
            [sade.util :as util]
            [sade.core :refer :all]
            [lupapalvelu.action :refer [defquery] :as action]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.user :refer [applicant?]]
            [lupapalvelu.application-meta-fields :as meta-fields]))

;; Operations

(defn- normalize-operation-name [i18n-text]
  (when-let [lc (ss/lower-case i18n-text)]
    (-> lc
      (s/replace #"\p{Punct}" "")
      (s/replace #"\s{2,}"    " "))))

(def operation-index
  (reduce
    (fn [ops [k _]]
      (let [localizations (map #(i18n/localize % "operations" (name k)) ["fi" "sv"])
            normalized (map normalize-operation-name localizations)]
        (conj ops {:op (name k) :locs (remove ss/blank? normalized)})))
    []
    operations/operations))

(defn- operation-names [filter-search]
  (let [normalized (normalize-operation-name filter-search)]
    (map :op
      (filter
        (fn [{locs :locs}] (some (fn [i18n-text] (.contains i18n-text normalized)) locs))
        operation-index))))

;;
;; Table definition
;;

(def- col-sources [(fn [app] (select-keys app [:urgency :authorityNotice]))
                            :indicators
                            :attachmentsRequiringAction
                            :unseenComments
                            (fn [app] (if (:infoRequest app) "inforequest" "application"))
                            (juxt :address :municipality)
                            meta-fields/get-application-operation
                            :applicant
                            :submitted
                            :modified
                            :state
                            :authority])

(def- order-by (assoc col-sources
                          0 nil
                          1 nil
                          2 nil
                          3 nil
                          4 :infoRequest
                          5 :address
                          6 nil
                          ; 7 applicant - sorted as is
                          ; 8 submitted - sorted as is
                          ; 9 modified - sorted as is
                          ; 10 state - sorted as is
                          11 ["authority.lastName" "authority.firstName"]
                          ))

(def- col-map (zipmap col-sources (map str (range))))

;;
;; Query construction
;;

(defn- make-free-text-query [filter-search]
  (let [or-query {$or [{:address {$regex filter-search $options "i"}}
                       {:verdicts.kuntalupatunnus {$regex filter-search $options "i"}}
                       {:_applicantIndex {$regex filter-search $options "i"}}]}
        ops (operation-names filter-search)]
    (if (seq ops)
      (update-in or-query [$or] conj {:operations.name {$in ops}})
      or-query)))

(defn- make-text-query [filter-search]
  {:pre [filter-search]}
  (cond
    (re-matches #"^([Ll][Pp])-\d{3}-\d{4}-\d{5}$" filter-search) {:_id (ss/upper-case filter-search)}
    (re-matches util/property-id-pattern filter-search) {:propertyId (util/to-property-id filter-search)}
    :else (make-free-text-query filter-search)))

(defn- make-query [query {:keys [filter-search filter-kind filter-state filter-user filter-username]} user]
  {$and
   (filter seq
     [query
      (when-not (ss/blank? filter-search) (make-text-query (ss/trim filter-search)))
      (merge
        (case filter-kind
          "applications" {:infoRequest false}
          "inforequests" {:infoRequest true}
          nil) ; defaults to both
        (let [all (if (applicant? user) {:state {$ne "canceled"}} {:state {$nin ["draft" "canceled"]}})]
          (case filter-state
           "application"       {:state {$in ["open" "submitted" "sent" "complement-needed" "info"]}}
           "construction"      {:state {$in ["verdictGiven" "constructionStarted"]}}
           "canceled"          {:state "canceled"}
           all))
        (when-not (ss/blank? filter-username)
          {$or [{"auth.username" filter-username}
                {"authority.username" filter-username}]})
        (when-not (contains? #{nil "0"} filter-user)
          {$or [{"auth.id" filter-user}
                {"authority.id" filter-user}]}))])})

(defn- make-sort [params]
  (let [col (get order-by (:iSortCol_0 params))
        dir (if (= "asc" (:sSortDir_0 params)) 1 -1)]
    (cond
      (nil? col) {}
      (sequential? col) (zipmap col (repeat dir))
      :else {col dir})))

;;
;; Result presentation
;;

(defn- add-field [application data [app-field data-field]]
  (assoc data data-field (app-field application)))

(defn- make-row [application]
  (let [base {"id" (:_id application)
              "kind" (if (:infoRequest application) "inforequest" "application")}]
    (reduce (partial add-field application) base col-map)))

;;
;; Public API
;;

(defn applications-for-user [user params]
  (let [user-query  (domain/basic-application-query-for user)
        user-total  (mongo/count :applications user-query)
        query       (make-query user-query params user)
        query-total (mongo/count :applications query)
        skip        (or (:iDisplayStart params) 0)
        limit       (or (:iDisplayLength params) 10)
        apps        (query/with-collection "applications"
                      (query/find query)
                      (query/sort (make-sort params))
                      (query/skip skip)
                      (query/limit limit))
        rows        (map (comp make-row (partial meta-fields/with-indicators user) #(domain/filter-application-content-for % user) ) apps)
        echo        (str (util/->int (str (:sEcho params))))] ; Prevent XSS
    {:aaData                rows
     :iTotalRecords         user-total
     :iTotalDisplayRecords  query-total
     :sEcho                 echo}))


;;
;; Service point for jQuery dataTables:
;;

(defquery applications-for-datatables
  {:parameters [params]
   :roles      [:applicant :authority]}
  [{user :user}]
  (ok :data (applications-for-user user params)))

;;
;; Regular query for integrations
;;

(defn- localize-application [application]
  (let [op-name (fn [op lang] (i18n/localize lang "operations" (:name op)))]
    (-> application
      (update-in [:operations] #(map (fn [op] (assoc op :displayNameFi (op-name op "fi") :displayNameSv (op-name op "sv"))) %))
      (assoc
        :stateNameFi (i18n/localize "fi" (:state application))
        :stateNameSv (i18n/localize "sv" (:state application))))))

(defquery applications
  {:parameters []
   :roles      [:applicant :authority]}
  [{user :user data :data}]
  (let [user-query (domain/basic-application-query-for user)
        query (make-query user-query data user)
        fields (concat [:id :location :infoRequest :address :municipality :operations :drawings :permitType] (filter keyword? col-sources))
        apps (mongo/select :applications query (zipmap fields (repeat 1)))
        rows (map #(-> %
                     (domain/filter-application-content-for user)
                     (select-keys fields) ; filters empty lists from previous step
                     localize-application)
               apps)]
    (ok :applications rows)))

(defn- public-fields [{:keys [municipality submitted operations]}]
  (let [op-name (-> operations first :name)]
    {:municipality municipality
    :timestamp submitted
    :operation (i18n/localize :fi "operations" op-name)
    :operationName {:fi (i18n/localize :fi "operations" op-name)
                    :sv (i18n/localize :sv "operations" op-name)}}))

(defquery latest-applications
  {:parameters []
   :roles      [:anonymous]}
  [_]
  (let [query {:submitted {$ne nil}}
        limit 5
        apps (query/with-collection "applications"
               (query/find query)
               (query/fields [:municipality :submitted :operations])
               (query/sort {:submitted -1})
               (query/limit limit))]
    (ok :applications (map public-fields apps))))
