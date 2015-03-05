(ns lupapalvelu.action
  (:require [taoensso.timbre :as timbre :refer [trace tracef debug debugf info infof warn warnf error errorf fatal fatalf]]
            [clojure.set :as set]
            [clojure.string :as s]
            [clojure.set :refer [difference union]]
            [slingshot.slingshot :refer [try+]]
            [sade.dns :as dns]
            [sade.env :as env]
            [sade.util :as util]
            [sade.strings :as ss]
            [sade.core :refer :all]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.logging :as log]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.domain :as domain]))

;;
;; construct command, query and raw
;;

(defn- action [name & {:keys [user type data] :or {:user nil :type :action :data {}}}]
  {:action name
   :user user
   :type type
   :created (now)
   :data data})


(defn make-command
  ([name data]      (make-command name nil data))
  ([name user data] (action name :user user :data data :type :command)))

(defn make-query  [name data] (action name :type :query :data data))
(defn make-raw    [name data] (action name :type :raw :data data))
(defn make-export [name data] (action name :type :export :data data))

;;
;; some utils
;;

(defn email-validator
  "Reads email key from action parameters and checks that it is valid email address.
   Blank address passes the validation."
  ([command] (email-validator :email command))
  ([email-param-name command]
    (let [email (get-in command [:data email-param-name])]
      (when-not (or (ss/blank? email)
                  (and
                    (util/valid-email? email)
                    (or (env/value :email :skip-mx-validation) (dns/valid-mx-domain? email))))
        (fail :error.email)))))

;; State helpers

(def all-application-states #{:draft :open :submitted :sent :complement-needed
                              :verdictGiven :constructionStarted :closed :canceled})
(def all-inforequest-states #{:info :answered})
(def all-states             (union all-application-states all-inforequest-states))

(def pre-verdict-states #{:draft :info :answered :open :submitted :complement-needed})
(def post-verdict-states (difference all-application-states pre-verdict-states))

(defn all-states-but [drop-states-array]
  (difference all-states (set drop-states-array)))

(defn all-application-states-but [drop-states-array]
  (difference all-application-states (set drop-states-array)))

(defn all-inforequest-states-but [drop-states-array]
  (difference all-inforequest-states (set drop-states-array)))

;; Role helpers

(def all-authz-writer-roles #{:owner :writer :statementGiver :foreman})
(def all-authz-roles (union all-authz-writer-roles #{:reader}))

;; Notificator

(defn notify [notification]
  (fn [command status]
    (notifications/notify! notification command)))

(defn with-application [command function]
  (if-let [id (-> command :data :id)]
    (if-let [application (:application command)]
      (function application)
      (fail :error.application-not-found :id id))
    (fail :error.application-not-found :id nil)))

(defn- filter-params-of-command [params command filter-fn error-message & [extra-error-data]]
  {:pre [(or (nil? extra-error-data) (map? extra-error-data))]}
  (when-let [non-matching-params (seq (filter #(filter-fn (get-in command [:data %])) params))]
    (merge
      (fail error-message :parameters (vec non-matching-params))
      extra-error-data)))

(defn non-blank-parameters [params command]
  (filter-params-of-command params command #(or (nil? %) (and (string? %) (s/blank? %))) :error.missing-parameters))

(defn vector-parameters [params command]
  (filter-params-of-command params command (complement vector?) :error.non-vector-parameters))

(defn vector-parameters-with-non-blank-items [params command]
  (or
    (vector-parameters params command)
    (filter-params-of-command params command
      (partial some #(or (nil? %) (and (string? %) (s/blank? %))))
      :error.vector-parameters-with-blank-items )))

(defn vector-parameters-with-map-items-with-required-keys [params required-keys command]
  (or
    (vector-parameters params command)
    (filter-params-of-command params command
      (partial some #(not (and (map? %) (util/every-key-in-map? % required-keys))))
      :error.vector-parameters-with-items-missing-required-keys
      {:required-keys required-keys})))

(defn boolean-parameters [params command]
  (filter-params-of-command params command #(not (instance? Boolean %)) :error.non-boolean-parameters))

(defn map-parameters [params command]
  (filter-params-of-command params command (complement map?) :error.unknown-type))

(defn update-application
  "Get current application from command (or fail) and run changes into it.
   Optionally returns the number of updated applications."
  ([command changes]
    (update-application command {} changes false))
  ([command mongo-query changes]
    (update-application command mongo-query changes false))
  ([command mongo-query changes return-count?]
    (with-application command
      (fn [{:keys [id]}]
        (let [n (mongo/update-by-query :applications (assoc mongo-query :_id id) changes)]
          (if return-count? n nil))))))

(defn application->command
  "Creates a command data structure that is suitable for update-application and with-application functions"
  [{id :id :as application}]
  {:data {:id id}
   :application application})

(defn without-system-keys [application]
  (into {} (filter (fn [[k v]] (not (.startsWith (name k) "_"))) application)))

;;
;; Actions
;;

(defonce actions (atom {}))

(defn get-actions [] @actions)

(defn serializable-actions []
  (into {} (for [[k v] (get-actions)]
             [k (-> v
                  (dissoc :handler :pre-checks :input-validators :on-success)
                  (assoc :name k))])))

;;
;; Command router
;;

(defn missing-fields [{data :data} {parameters :parameters}]
  (map name (set/difference (set parameters) (set (keys data)))))

(defn- has-required-user-role [command {user-roles :user-roles :as meta-data}]
  {:pre [(set? user-roles)]}
  (let [user-role (-> command :user :role keyword)]
    (or (user-roles :anonymous) (user-roles user-role))))

(defn meta-data [{command :action}]
  ((get-actions) (keyword command)))

(defn missing-command [command]
  (when-not (meta-data command)
    (warnf "command '%s' not found" (:action command))
    (fail :error.invalid-command)))

(defn missing-feature [command]
  (when-let [feature (:feature (meta-data command))]
    (when-not (env/feature? feature)
      (fail :error.missing-feature))))

(defn invalid-type [{type :type :as command}]
  (when (and type (not= type (:type (meta-data command))))
    (info "invalid type:" (name type))
    (fail :error.invalid-type)))

(defn missing-roles [command]
  (when-not (has-required-user-role command (meta-data command))
    (tracef "command '%s' is unauthorized for role '%s'" (:action command) (-> command :user :role))
    unauthorized))

(defn- impersonation [command]
  (when (and (= :command (:type (meta-data command))) (get-in command [:user :impersonating]))
    unauthorized))

(defn disallow-impersonation [command _]
  (when (get-in command [:user :impersonating]) unauthorized))

(defn missing-parameters [command]
  (when-let [missing (seq (missing-fields command (meta-data command)))]
    (info "missing parameters:" (s/join ", " missing))
    (fail :error.missing-parameters :parameters (vec missing))))

(defn input-validators-fail [command]
  (when-let [validators (:input-validators (meta-data command))]
    (when (seq validators)
      (reduce #(or %1 (%2 command)) nil validators))))

(defn invalid-state-in-application [command {state :state}]
  (when-let [valid-states (:states (meta-data command))]
    (when-not (.contains valid-states (keyword state))
      (fail :error.command-illegal-state :state state))))

(defn pre-checks-fail [command application]
  (when-let [pre-checks (:pre-checks (meta-data command))]
    (reduce #(or %1 (%2 command application)) nil pre-checks)))

(defn masked [command]
  (letfn [(strip-field [command field]
            (if (get-in command [:data field])
              (assoc-in command [:data field] "*****")
              command))]
    (reduce strip-field command [:password :newPassword :oldPassword])))

(defn get-meta [name]
  ((keyword name) (get-actions)))

(defn executed
  ([command] (executed (:action command) command))
  ([name command]
    (let [meta-data (get-meta name)]
      (or
        (if-let [handler (:handler meta-data)]
          (let [result (handler command)
                masked-command (masked command)]
            (if (or (= :raw (:type command)) (nil? result) (ok? result))
              (log/log-event :info masked-command)
              (log/log-event :warning masked-command))
            result)
          (infof "no handler for action '%s'" name))
        (ok)))))

(def authorize-validators [missing-command
                           missing-feature
                           missing-roles
                           impersonation])

(def execute-validators (conj authorize-validators
                          invalid-type
                          missing-parameters
                          input-validators-fail))

(defn requires-application? [{data :data}]
  (contains? data :id))

(defn- get-application
  "if :id parameter is present read application from command
   (pre-loaded) or load application for user."
  [{{id :id} :data user :user application :application}]
  (and id user (or application (domain/get-application-as id user true))))

(defn- user-is-not-allowed-to-access?
  "Current user must be owner, authority or writer OR have some other supplied extra-auth-roles"
  [{user :user :as command} application]
  (let [meta-data (meta-data command)
        extra-auth-roles (set (:extra-auth-roles meta-data))]
    (when-not (or (extra-auth-roles :any)
                  (domain/owner-or-write-access? application (:id user))
                  (and (= :authority (keyword (:role user))) ((set (:organizations user)) (:organization application)))
                  (some #(domain/has-auth-role? application (:id user) %) extra-auth-roles))
      unauthorized)))

(defn- not-authorized-to-application [command application]
  (when (-> command :data :id)
    (if-not application
      (fail :error.application-not-accessible)
      (or
        (invalid-state-in-application command application)
        (user-is-not-allowed-to-access? command application)))))

(defn- response? [r]
  (and (map? r) (:status r)))

(defn get-post-fns [{ok :ok} {:keys [on-complete on-success on-fail]}]
  (letfn [(->vec [v]
            (cond
              (nil? v)         nil
              (sequential? v)  v
              :else            [v]))]
    (concat (->vec on-complete) (->vec (if ok on-success on-fail)))))

(defn invoke-post-fns! [fns command status]
  (doseq [f fns]
    (try
      (f command status)
      (catch Throwable e
        (error e "post fn fail")))))

(defn- run [command validators execute?]
  (try+
    (or
      (some #(% command) validators)
      (let [application (get-application command)]
        (or
          (not-authorized-to-application command application)
          (pre-checks-fail command application)
          (when execute?
            (let [command  (assoc command :application application) ;; cache the app
                  status   (executed command)
                  post-fns (get-post-fns status (get-meta (:action command)))]
              (invoke-post-fns! post-fns command status)
              status))
          (ok))))
    (catch [:sade.core/type :sade.core/fail] {:keys [text] :as all}
      (do
        (errorf "fail! in action: \"%s\" [%s:%d]: %s (%s)"
          (:action command)
          (:sade.core/file all)
          (:sade.core/line all)
          text
          (dissoc all :text :sade.core/type :sade.core/file :sade.core/line))
        (when execute? (log/log-event :error command))
        (fail text (dissoc all :sade.core/type :sade.core/file :sade.core/line))))
    (catch response? resp
      (do
        (warnf "%s -> proxy fail: %s" (:action command) resp)
        (fail :error.unknown)))
    (catch Object e
      (do
        (error e "exception while processing action:" (:action command) (class e) (str e))
        (when execute? (log/log-event :error command))
        (fail :error.unknown)))))

(defn execute [{action :action :as command}]
  (let [response (run command execute-validators true)]
    (debug action "->" (:ok response))
    (swap! actions update-in [(keyword action) :call-count] #(if % (inc %) 1))
    response))

(defn validate [command]
  (run command authorize-validators false))

;;
;; Register actions
;;

(def supported-action-meta-data
  {:parameters  "Vector of parameters. Parameters can be keywords or symbols. Symbols will be available in the action body. If a parameter is missing from request, an error will be raised."
   :user-roles  "Set of user role keywords."
   :extra-auth-roles  "Vector of role keywords."
   :description "Documentation string."
   :notified    "Boolean. Documents that the action will be sending (email) notifications."
   :pre-checks  "Vector of functions."
   :input-validators  "Vector of functions."
   :states      "Vector of application state keywords"
   :on-complete "Function or vector of functions."
   :on-success  "Function or vector of functions."
   :on-fail     "Function or vector of functions."
   :feature     "Keyword: feature flag name. Action is run only if the feature flag is true.
                 If you have feature.some-feature properties file, use :feature :some-feature in action meta data"})

(def- supported-action-meta-data-keys (set (keys supported-action-meta-data)))

(defn register-action [action-type action-name meta-data line ns-str handler]
  {:pre [action-type action-name meta line handler]}

  ;(assert (.endsWith ns-str "-api") (str "Actions must be defined in *-api namespaces"))

  (assert (not (ss/blank? (name action-type))))
  (assert (not (ss/blank? (name action-name))))
  (assert (every? supported-action-meta-data-keys (keys meta-data)) (str (keys meta-data)))
  (assert (if (some #(= % :id) (:parameters meta-data)) (seq (:states meta-data)) true)
    (str "You must define :states meta data for " action-name " if action has the :id parameter (i.e. application is attached to the action)."))

  (let [action-keyword (keyword action-name)
        user-roles (:user-roles meta-data)]

    (assert (seq user-roles) (str "You must define :user-roles meta data for " action-name ". Use :user-roles #{:anonymous}] to grant access to anyone."))
    (assert (and (set? user-roles) (every? keyword? user-roles)) ":user-roles must be a set of keywords")

    (tracef "registering %s: '%s' (%s:%s)" (name action-type) action-name ns-str line)
    (swap! actions assoc
      action-keyword
      (merge meta-data {:type action-type
                        :ns ns-str
                        :line line
                        :handler handler
                        :call-count 0}))))

(defmacro defaction [form-meta action-type action-name & args]
  (let [doc-string  (when (string? (first args)) (first args))
        args        (if doc-string (rest args) args)
        meta-data   (when (map? (first args)) (first args))
        args        (if meta-data (rest args) args)
        bindings    (when (vector? (first args)) (first args))
        body        (if bindings (rest args) args)
        bindings    (or bindings ['_])
        parameters  (:parameters meta-data)
        letkeys     (filter symbol? parameters)
        parameters  (map (comp keyword name) parameters)
        meta-data   (assoc meta-data :parameters (vec parameters))
        line-number (:line form-meta)
        ns-str      (str *ns*)
        defname     (symbol (str (name action-type) "-" action-name))
        handler     (eval
                      `(fn [request#]
                         (let [{{:keys ~letkeys} :data} request#]
                           ((fn ~bindings (do ~@body)) request#))))]
    `(do
       (register-action ~action-type ~(str action-name) ~meta-data ~line-number ~ns-str ~handler)
       (defn ~defname
         ([] (~defname {}))
         ([request#] (~handler request#))))))

(defmacro defcommand [& args] `(defaction ~(meta &form) :command ~@args))
(defmacro defquery   [& args] `(defaction ~(meta &form) :query ~@args))
(defmacro defraw     [& args] `(defaction ~(meta &form) :raw ~@args))
(defmacro defexport  [& args] `(defaction ~(meta &form) :export ~@args))

