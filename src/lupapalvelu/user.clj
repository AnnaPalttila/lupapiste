(ns lupapalvelu.user
  (:require [taoensso.timbre :as timbre :refer [debug debugf info warn warnf]]
            [clj-time.core :as time]
            [clj-time.coerce :refer [to-date]]
            [monger.operators :refer :all]
            [monger.query :as query]
            [camel-snake-kebab :as kebab]
            [sade.core :refer [fail fail! now]]
            [sade.env :as env]
            [sade.strings :as ss]
            [sade.util :as util]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.security :as security]))

;;
;; ==============================================================================
;; Utils:
;; ==============================================================================
;;

(defn non-private
  "Returns user without private details."
  [user]
  (dissoc user :private))

(defn summary
  "Returns common information about the user or nil"
  [user]
  (when user
    (select-keys user [:id :username :firstName :lastName :role])))

(defn coerce-org-authz
  "Coerces orgAuthz to schema {Keyword #{Str}}"
  [org-authz]
  (into {} (for [[k v] org-authz] [k (set v)])))

(defn with-org-auth [user]
  (update-in user [:orgAuthz] coerce-org-authz))

(defn session-summary
  "Returns common information about the user to be stored in session or nil"
  [user]
  (some-> user
    (select-keys [:id :username :firstName :lastName :role :email :organizations :company :architect :orgAuthz])
    with-org-auth
    (assoc :expires (+ (now) (.toMillis java.util.concurrent.TimeUnit/MINUTES 5)))))

(defn virtual-user?
  "True if user exists only in session, not in database"
  [{:keys [role impersonating]}]
  (or
    impersonating
    (contains? #{:oirAuthority} (keyword role))))

(defn authority? [{role :role}]
  (#{:authority :oirAuthority} (keyword role)))

(defn applicant? [{role :role}]
  (= :applicant (keyword role)))

(defn same-user? [{id1 :id} {id2 :id}]
  (= id1 id2))

(def canonize-email (comp ss/lower-case ss/trim))

(defn organization-ids-by-roles
  "Returns organization IDs where user has given roles."
  [{org-authz :orgAuthz :as user} roles]
  {:pre [(set? roles) (every? keyword? roles)]}
  (->> org-authz
    (filter (fn [[org org-roles]] (some roles org-roles)))
    (map (comp name first))))

(defn authority-admins-organization-id [user]
  (first (organization-ids-by-roles user #{:authorityAdmin})))


;;
;; ==============================================================================
;; Finding user data:
;; ==============================================================================
;;

(defn- user-query [query]
  {:pre [(map? query)]}
  (let [query (if-let [id (:id query)]
                (-> query
                  (assoc :_id id)
                  (dissoc :id))
                query)
        query (if-let [username (:username query)]
                (assoc query :username (canonize-email username))
                query)
        query (if-let [email (:email query)]
                (assoc query :email (canonize-email email))
                query)
        query (if-let [organization (:organization query)]
                (-> query
                  (assoc :organizations organization)
                  (dissoc :organization))
                query)]
    query))

(defn find-user [query]
  (mongo/select-one :users (user-query query)))

(defn find-users [query]
  (mongo/select :users (user-query query)))

;;
;; jQuery data-tables support:
;;

(defn- users-for-datatables-base-query [caller params]
  (let [admin?               (= (-> caller :role keyword) :admin)
        caller-organizations (set (:organizations caller))
        organizations        (:organizations params)
        organizations        (if admin? organizations (filter caller-organizations (or organizations caller-organizations)))
        role                 (:filter-role params)
        role                 (if admin? role :authority)
        enabled              (if admin? (:filter-enabled params) true)]
    (merge {}
      (when organizations       {:organizations {$in organizations}})
      (when role                {:role role})
      (when-not (nil? enabled)  {:enabled enabled}))))

(defn- users-for-datatables-query [base-query {:keys [filter-search]}]
  (if (ss/blank? filter-search)
    base-query
    (let [searches (ss/split filter-search #"\s+")]
      (assoc base-query $and (map (fn [t]
                                    {$or (map hash-map
                                           [:email :firstName :lastName]
                                           (repeat t))})
                               (map re-pattern searches))))))

(defn users-for-datatables [caller params]
  (let [base-query       (users-for-datatables-base-query caller params)
        base-query-total (mongo/count :users base-query)
        query            (users-for-datatables-query base-query params)
        query-total      (mongo/count :users query)
        users            (query/with-collection "users"
                           (query/find query)
                           (query/fields [:email :firstName :lastName :role :organizations :enabled])
                           (query/skip (util/->int (:iDisplayStart params) 0))
                           (query/limit (util/->int (:iDisplayLength params) 16)))]
    {:rows     users
     :total    base-query-total
     :display  query-total
     :echo     (str (util/->int (str (:sEcho params))))}))

;;
;; ==============================================================================
;; Login throttle
;; ==============================================================================
;;

(defn- logins-lock-expires-date []
  (to-date (time/minus (time/now) (time/seconds (env/value :login :throttle-expires)))))

(defn throttle-login? [username]
  {:pre [username]}
  (mongo/any? :logins {:_id (canonize-email username)
                       :failed-logins {$gte (env/value :login :allowed-failures)}
                       :locked {$gt (logins-lock-expires-date)}}))

(defn login-failed [username]
  {:pre [username]}
  (mongo/remove-many :logins {:locked {$lte (logins-lock-expires-date)}})
  (mongo/update :logins {:_id (canonize-email username)}
                {$set {:locked (java.util.Date.)}, $inc {:failed-logins 1}}
                :multi false
                :upsert true))

(defn clear-logins [username]
  {:pre [username]}
  (mongo/remove :logins (canonize-email username)))

;;
;; ==============================================================================
;; Getting non-private user data:
;; ==============================================================================
;;

(defn get-user [q]
  (non-private (find-user q)))

(defn get-users [q]
  (map non-private (find-users q)))

(defn get-user-by-id [id]
  {:pre [id]}
  (get-user {:id id}))

(defn get-user-by-id! [id]
  (or (get-user-by-id id) (fail! :not-found)))

(defn get-user-by-email [email]
  {:pre [email]}
  (get-user {:email email}))

(defn get-user-with-password [username password]
  (when-not (or (ss/blank? username) (ss/blank? password))
    (let [user (find-user {:username username})]
     (when (and user (:enabled user) (security/check-password password (get-in user [:private :password])))
       (non-private user)))))

(defn get-user-with-apikey [apikey]
  (when-not (ss/blank? apikey)
    (let [user (find-user {:private.apikey apikey})]
      (when (:enabled user)
        (session-summary user)))))

(defmacro with-user-by-email [email & body]
  `(let [~'user (get-user-by-email ~email)]
     (when-not ~'user
       (debugf "user '%s' not found with email" ~email)
       (fail! :error.user-not-found :email ~email))
     ~@body))

;;
;; ==============================================================================
;; User role:
;; ==============================================================================
;;

(defn applicationpage-for [role]
  (let [s (name role)]
    (cond
      (or (ss/blank? s) (= s "dummy")) "applicant"
      (= s "oirAuthority") "oir"
      :else (kebab/->kebab-case s))))

(defn user-in-role [user role & params]
  (merge (apply hash-map params) (assoc (summary user) :role role)))

;;
;; ==============================================================================
;; Current user:
;; ==============================================================================
;;

(defn current-user
  "fetches the current user from session"
  [request] (:user request ))

;;
;; ==============================================================================
;; Creating API keys:
;; ==============================================================================
;;

(defn create-apikey
  "Add or replace users api key. User is identified by email. Returns apikey. If user is unknown throws an exception."
  [email]
  (let [apikey (security/random-password)
        n      (mongo/update-n :users {:email (canonize-email email)} {$set {:private.apikey apikey}})]
    (when-not (= n 1) (fail! :unknown-user :email email))
    apikey))

;;
;; ==============================================================================
;; Change password:
;; ==============================================================================
;;

(defn change-password
  "Update users password. Returns nil. If user is not found, raises an exception."
  [email password]
  (let [salt              (security/dispense-salt)
        hashed-password   (security/get-hash password salt)
        email             (canonize-email email)
        updated-user      (mongo/update-one-and-return :users
                            {:email email}
                            {$set {:private.password hashed-password
                                   :enabled true}})]
    (if updated-user
      (do
        (mongo/remove-many :activation {:email email})
        (clear-logins (:username updated-user)))
      (fail! :unknown-user :email email))
    nil))

;;
;; ==============================================================================
;; Updating user information:
;; ==============================================================================
;;

(defn update-user-by-email [email data]
  (mongo/update :users {:email (canonize-email email)} {$set data}))

(defn update-organizations-of-authority-user [email new-organization]
  (let [old-orgs (:organizations (get-user-by-email email))]
    (when (every? #(not= % new-organization) old-orgs)
      (update-user-by-email email {:organizations (merge old-orgs new-organization)}))))


;;
;; ==============================================================================
;; Other:
;; ==============================================================================
;;

;;
;; Link user to company:
;;

(defn link-user-to-company! [user-id company-id role]
  (mongo/update :users {:_id user-id} {$set {:company {:id company-id, :role role}}}))
