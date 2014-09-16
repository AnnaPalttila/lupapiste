(ns lupapalvelu.vetuma
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error errorf fatal]]
            [clojure.set :refer [rename-keys]]
            [clojure.string :as string]
            [noir.core :refer [defpage]]
            [noir.request :as request]
            [noir.response :refer [redirect status json]]
            [hiccup.core :refer [html]]
            [hiccup.form :as form]
            [monger.operators :refer :all]
            [clj-time.local :refer [local-now]]
            [clj-time.format :as format]
            [pandect.core :as pandect]
            [sade.env :as env]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.vtj :as vtj]))

;;
;; Configuration
;;

(def encoding "ISO-8859-1")

(def request-mac-keys  [:rcvid :appid :timestmp :so :solist :type :au :lg :returl :canurl :errurl :ap :extradata :appname :trid])
(def response-mac-keys [:rcvid :timestmp :so :userid :lg :returl :canurl :errurl :subjectdata :extradata :status :trid :vtjdata])

(defn config []
  {:url       (env/value :vetuma :url)
   :rcvid     (env/value :vetuma :rcvid)
   :appid     "Lupapiste"
   :so        "6"
   :solist    "6" #_"6,11"
   :type      "LOGIN"
   :au        "EXTAUTH"
   :lg        "fi"
   :returl    "{host}/api/vetuma"
   :canurl    "{host}/api/vetuma/cancel"
   :errurl    "{host}/api/vetuma/error"
   :ap        (env/value :vetuma :ap)
   :appname   "Lupapiste"
   :extradata "VTJTT=VTJ-VETUMA-Perus"
   :key       (env/value :vetuma :key)})

;; log error for all missing env keys.
(doseq [[k v] (config)]
  (when (nil? v) (errorf "missing key '%s' value from property file" (name k))))

;;
;; Helpers
;;

(def time-format (format/formatter-local "yyyyMMddHHmmssSSS"))

(defn- timestamp [] (format/unparse time-format (local-now)))

(defn- generate-stamp [] (apply str (repeatedly 20 #(rand-int 10))))

(defn- keys-as [f m] (into {} (for [[k v] m] [(f k) v])))
(defn- keys-as-strings [m] (keys-as #(.toUpperCase (name %)) m))
(defn- keys-as-keywords [m] (keys-as #(keyword (.toLowerCase %)) m))

(defn- logged [m]
  (info (-> m (dissoc "ERRURL") (dissoc :errurl) str))
  m)

(defn apply-template
  "changes all variables in braces {} with keywords with same name.
   for example (apply-template \"hi {name}\" {:name \"Teppo\"}) returns \"hi Teppo\""
  [v m] (string/replace v #"\{(\w+)\}" (fn [[_ word]] (or (m (keyword word)) ""))))

(defn apply-templates
  "runs apply-template on all values, using the map as input"
  [m] (into {} (for [[k v] m] [k (apply-template v m)])))

;;
;; Mac
;;

(defn- secret [{rcvid :rcvid key :key}] (str rcvid "-" key))
(defn mac [data]  (-> data (.getBytes encoding) pandect/sha256 .toUpperCase))

(defn- mac-of [m keys]
  (->
    (for [k keys] (k m))
    vec
    (conj (secret m))
    (conj "")
    (->> (string/join "&"))
    mac))

(defn- with-mac [m]
  (merge m {:mac (mac-of m request-mac-keys)}))

(defn- mac-verified [{:keys [mac] :as m}]
  (if (= mac (mac-of m response-mac-keys))
    m
    (do (error "invalid mac: " (dissoc m :key))
      (throw (IllegalArgumentException. "invalid mac.")))))

;;
;; response parsing
;;

(defn extract-subjectdata [{s :subjectdata}]
  (-> s
    (string/split #", ")
    (->> (map #(string/split % #"=")))
    (->> (into {}))
    keys-as-keywords
    (rename-keys {:etunimi :firstname})
    (rename-keys {:sukunimi :lastname})))

(defn- extract-vtjdata [{:keys [vtjdata]}]
  (vtj/extract-vtj vtjdata))

(defn- extract-userid [{s :extradata}]
  {:userid (last (string/split s #"="))})

(defn- extract-request-id [{id :trid}]
  {:pre [id]}
  {:stamp id})

(defn user-extracted [m]
  (merge (extract-subjectdata m)
         (extract-vtjdata m)
         (extract-userid m)
         (extract-request-id m)))

;;
;; Request & Response mapping to clojure
;;

(defn request-data [host]
  (-> (config)
    (assoc :trid (generate-stamp))
    (assoc :timestmp (timestamp))
    (assoc :host  host)
    apply-templates
    with-mac
    (dissoc :key)
    (dissoc :url)
    (dissoc :host)
    keys-as-strings))

(defn parsed [m]
  (-> m
    keys-as-keywords
    (assoc :key (:key (config)))
    mac-verified
    (dissoc :key)))

;;
;; Web stuff
;;

(defn session-id [] (get-in (request/ring-request) [:cookies "ring-session" :value]))

(defn- field [[k v]]
  (form/hidden-field k v))

(defn- non-local? [paths] (some #(not= -1 (.indexOf (or % "") ":")) (vals paths)))

(defn host-and-ssl-port
  "returns host with port changed from 8000 to 8443. Shitty crap."
  [host] (string/replace host #":8000" ":8443"))

(defn host
  ([] (host :current))
  ([mode]
    (let [request (request/ring-request)
          scheme  (name (:scheme request))
          hostie  (get-in request [:headers "host"])]
      (case mode
        :current (str scheme "://" hostie)
        :secure  (if (= scheme "https")
                   (host :current)
                   (str "https://" (host-and-ssl-port hostie)))))))

(defpage "/api/vetuma" {:keys [success, cancel, error] :as data}
  (let [paths     {:success success :error error :cancel cancel}
        sessionid (session-id)]
    (if (non-local? paths)
      (status 400 (format "invalid return paths: %s" paths))
      (do
        (mongo/update-one-and-return :vetuma {:sessionid sessionid} {:sessionid sessionid :paths paths :created-at (java.util.Date.)} :upsert true)
        (html
          (form/form-to [:post (:url (config))]
            (map field (request-data (host :secure)))
            (form/submit-button "submit")))))))

(defpage [:post "/api/vetuma"] []
  (let [user (-> (:form-params (request/ring-request))
               logged
               parsed
               user-extracted
               logged)
        data (mongo/update-one-and-return :vetuma {:sessionid (session-id)} {$set {:user user}})
        uri  (get-in data [:paths :success])]
    (if uri
      (redirect uri)
      (redirect (str (host) "/app/fi/welcome#!/register2")))))

(def ^:private error-status-codes
  ; From Vetuma_palvelun_kutsurajapinnan_maarittely_v3_0.pdf
  {"REJECTED" "Kutsun palveleminen ep\u00e4onnistui, koska se k\u00e4ytt\u00e4j\u00e4n valitsema vuorovaikutteinen taustapalvelu johon Vetuma-palvelu ohjasi k\u00e4ytt\u00e4j\u00e4n toimintoa suorittamaan hylk\u00e4si toiminnon suorittaminen."
   "ERROR" "Kutsu oli virheellinen."
   "FAILURE" "Kutsun palveleminen ep\u00e4onnistui jostain muusta syyst\u00e4 kuin siit\u00e4, ett\u00e4 taustapalvelu hylk\u00e4si suorittamisen."})

(defpage [:any "/api/vetuma/:status"] {status :status}
  (let [data         (mongo/select-one :vetuma {:sessionid (session-id)})
        params       (:form-params (request/ring-request))
        status-param (get params "STATUS")
        return-uri   (get-in data [:paths (keyword status)])
        return-uri   (or return-uri "/")]

    (case status
      "cancel" (info "Vetuma cancel")
      "error"  (error "Vetuma failure, STATUS =" status-param "=" (get error-status-codes status-param) "Request parameters:" (keys-as-keywords params))
      (error "Unknown status:" status))

    (redirect return-uri)))

(defpage "/api/vetuma/user" []
  (let [data (mongo/select-one :vetuma {:sessionid (session-id)})
        user (:user data)]
    (json user)))

;;
;; public local api
;;

(defn- get-data [stamp]
  (mongo/select-one :vetuma {:user.stamp stamp}))

(defn get-user [stamp]
  (:user (get-data stamp)))

(defn consume-user [stamp]
  (when-let [user (get-data stamp)]
    (mongo/remove-many :vetuma {:_id (:id user)})
    (:user user)))

;;
;; dev test api
;;

(env/in-dev
  (defpage "/dev/api/vetuma" {:as data}
    (let [stamp (generate-stamp)
          user  (select-keys data [:userid :firstname :lastname])
          user  (assoc user :stamp stamp)]
      (mongo/insert :vetuma {:user user :created-at (java.util.Date.)})
      (json user))))
