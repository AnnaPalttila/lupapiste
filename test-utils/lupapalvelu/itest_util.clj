(ns lupapalvelu.itest-util
  (:use [lupapalvelu.fixture.minimal :only [users]]
        [lupapalvelu.core :only [fail!]]
        [clojure.walk :only [keywordize-keys]]
        [swiss-arrows.core]
        [midje.sweet])
  (:require [clj-http.client :as c]
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal)]
            [lupapalvelu.vetuma :as vetuma]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [sade.util :refer [fn-> fn->>]]))

(defn- find-user [username] (some #(when (= (:username %) username) %) users))
(defn- id-for [username] (:id (find-user username)))
(defn- apikey-for [username] (get-in (find-user username) [:private :apikey]))

(def pena        (apikey-for "pena"))
(def pena-id     (id-for "pena"))
(def mikko       (apikey-for "mikko@example.com"))
(def mikko-id    (id-for "mikko@example.com"))
(def teppo       (apikey-for "teppo@example.com"))
(def teppo-id    (id-for "teppo@example.com"))
(def veikko      (apikey-for "veikko"))
(def veikko-id   (id-for "veikko"))
;TODO should get this through organization
(def veikko-muni "837")
(def sonja       (apikey-for "sonja"))
(def sonja-id    (id-for "sonja"))
;TODO should get this through organization
(def sonja-muni  "753")
(def sipoo       (apikey-for "sipoo"))
(def dummy       (apikey-for "dummy"))

(defn server-address [] (System/getProperty "target_server" "http://localhost:8000"))

(defn decode-response [resp]
  (assoc resp :body (-> (:body resp) json/decode keywordize-keys)))

(defn printed [x] (println x) x)

(defn raw [apikey action & args]
  (c/get
    (str (server-address) "/api/raw/" (name action))
    {:headers {"authorization" (str "apikey=" apikey)}
     :query-params (apply hash-map args)
     :throw-exceptions false}))

(defn raw-query [apikey query-name & args]
  (decode-response
    (c/get
      (str (server-address) "/api/query/" (name query-name))
      {:headers {"authorization" (str "apikey=" apikey)
                 "accepts" "application/json;charset=utf-8"}
       :query-params (apply hash-map args)
       :follow-redirects false
       :throw-exceptions false})))

(defn query [apikey query-name & args]
  (let [{status :status body :body} (apply raw-query apikey query-name args)]
    (when (= status 200)
      body)))

(defn raw-command [apikey command-name & args]
  (decode-response
    (c/post
      (str (server-address) "/api/command/" (name command-name))
      {:headers {"authorization" (str "apikey=" apikey)
                 "content-type" "application/json;charset=utf-8"}
       :body (json/encode (apply hash-map args))
       :follow-redirects false
       :throw-exceptions false})))

(defn command [apikey command-name & args]
  (let [{status :status body :body} (apply raw-command apikey command-name args)]
    (when (= status 200)
      body)))

(defn apply-remote-fixture [fixture-name]
  (let [resp (decode-response (c/get (str (server-address) "/dev/fixture/" fixture-name)))]
    (assert (-> resp :body :ok))))

(def apply-remote-minimal (partial apply-remote-fixture "minimal"))

(defn create-app [apikey & args]
  (let [args (->> args
               (apply hash-map)
               (merge {:operation "asuinrakennus"
                       :propertyId "75312312341234"
                       :x 444444 :y 6666666
                       :address "foo 42, bar"
                       :municipality "753"})
               (mapcat seq))]
    (apply command apikey :create-application args)))

(defn create-YA-app [apikey & args] (apply create-app apikey (concat args [:operation "mainostus-ja-viitoituslupa"])))
(defn create-R-app [apikey & args]  (apply create-app apikey (concat args [:operation "asuinrakennus"])))

(defn success [resp]
  (fact (:text resp) => nil)
  (:ok resp))

(defn unauthorized [resp]
  (fact (:text resp) => "error.unauthorized")
  (= (:ok resp) false))

(defn invalid-csrf-token? [{:keys [status body]}]
  (and
    (= status 403)
    (= (:ok body) false)
    (= (:text body) "error.invalid-csrf-token")))

;;
;; Test predicates: TODO: comment test, add facts to get real cause
;;

(fact "invalid-csrf-token?"
  (invalid-csrf-token? {:status 403 :body {:ok false :text "error.invalid-csrf-token"}}) => true
  (invalid-csrf-token? {:status 403 :body {:ok false :text "error.SOME_OTHER_REASON"}}) => false
  (invalid-csrf-token? {:status 200 :body {:ok true}}) => false)

(defn unauthorized? [{:keys [ok text]}]
  (and (= ok false) (= text "error.unauthorized")))

(fact "unauthorized?"
  (unauthorized? {:ok false :text "error.unauthorized"}) => true
  (unauthorized? {:ok false :text "error.SOME_OTHER_REASON"}) => false
  (unauthorized? {:ok true}) => false)

(defn in-state? [state]
  (fn [application] (= (:state application) (name state))))

(fact "in-state?"
  ((in-state? :open) {:state "open"}) => true
  ((in-state? :open) {:state "closed"}) => false)

(defn ok? [resp]
  (= (:ok resp) true))

(fact "ok?"
  (ok? {:ok true}) => true
  (ok? {:ok false}) => false)

(defn http200? [{:keys [status]}]
  (= status 200))

(defn http401? [{:keys [status]}]
  (= status 401))

(defn http404? [{:keys [status]}]
  (= status 404))

;;
;; DSLs
;;

(defn set-anti-csrf! [value] (query pena :set-feature :feature "disable-anti-csrf" :value (not value)))
(defn feature? [& feature]
  (boolean (-<>> :features (query pena) :features (into {}) (get <> (map name feature)))))

(defmacro with-anti-csrf [& body]
  `(let [old-value# (feature? :disable-anti-csrf)]
     (set-anti-csrf! true)
     (try
       (do ~@body)
       (finally
         (set-anti-csrf! old-value#)))))

(defn create-app-id [apikey & args]
  (let [resp (apply create-app apikey args)
        id   (:id resp)]
    resp => ok?
    id => truthy
    id))

(defn create-and-submit-application [apikey & args]
  (let [id    (apply create-app-id apikey args)
        resp  (command apikey :submit-application :id id) => ok?
        resp  (query pena :application :id id) => ok?
        app   (:application resp)]
    app))

(defn comment-application [id apikey]
  (fact "comment is added succesfully"
    (command apikey :add-comment :id id :text "hello" :target "application") => ok?))

(defn query-application [apikey id]
  (let [{application :application :as response} (query apikey :application :id id)]
    response => ok?
    application))

(defn allowed? [action & args]
  (fn [apikey]
    (let [{:keys [ok actions]} (apply query apikey :allowed-actions args)
          allowed? (-> actions action :ok)]
      (and ok allowed?))))

;;
;; Stuffin' data in
;;

(defn upload-attachment [apikey application-id attachment-id expect-to-succeed permit-type]
  (let [filename    "dev-resources/test-attachment.txt"
        uploadfile  (io/file filename)
        uri         (str (server-address) "/api/upload/attachment")
        resp        (c/post uri
                      {:headers {"authorization" (str "apikey=" apikey)}
                       :multipart [{:name "applicationId"  :content application-id}
                                   {:name "Content/type"   :content "text/plain"}
                                   {:name "attachmentType" :content (condp = (keyword permit-type)
                                                                      :R "paapiirustus.asemapiirros"
                                                                      :YA "yleiset-alueet.tieto-kaivupaikkaan-liittyvista-johtotiedoista"
                                                                      :Y "paapiirustus.asemapiirros"  ;;TODO: Change this
                                                                      :P "paapiirustus.asemapiirros"  ;;TODO: Change this
                                                                      (fail! "unsupported permit-type"))}
                                   {:name "attachmentId"   :content attachment-id}
                                   {:name "upload"         :content uploadfile}]})]
    (if expect-to-succeed
      (facts "Upload succesfully"
        (fact "Status code" (:status resp) => 302)
        (fact "location"    (get-in resp [:headers "location"]) => "/html/pages/upload-ok.html"))
      (facts "Upload should fail"
        (fact "Status code" (:status resp) => 302)
        (fact "location"    (.indexOf (get-in resp [:headers "location"]) "/html/pages/upload-1.0.5.html") => 0)))))

(defn upload-attachment-for-statement [apikey application-id attachment-id expect-to-succeed statement-id]
  (let [filename    "dev-resources/test-attachment.txt"
        uploadfile  (io/file filename)
        application (query apikey :application :id application-id)
        uri         (str (server-address) "/api/upload/attachment")
        resp        (c/post uri
                      {:headers {"authorization" (str "apikey=" apikey)}
                       :multipart [{:name "applicationId"  :content application-id}
                                   {:name "Content/type"   :content "text/plain"}
                                   {:name "attachmentType" :content "muut.muu"}
                                   {:name "attachmentId"   :content attachment-id}
                                   {:name "upload"         :content uploadfile}
                                   {:name "targetId"       :content statement-id}
                                   {:name "targetType"     :content "statement"}]}
                      )]
    (if expect-to-succeed
      (facts "Statement upload succesfully"
        (fact "Status code" (:status resp) => 302)
        (fact "location"    (get-in resp [:headers "location"]) => "/html/pages/upload-ok.html"))
      ;(facts "Statement upload should fail"
       ; (fact "Status code" (:status resp) => 302)
      ;  (fact "location"    (.indexOf (get-in resp [:headers "location"]) "/html/pages/upload-1.0.5.html") => 0))
      )))


(defn get-attachment-ids [application] (->> application :attachments (map :id)))

(defn upload-attachment-to-all-placeholders [apikey application]
  (doseq [attachment-id (get-attachment-ids application)]
    (upload-attachment pena (:id application) attachment-id true "R")))

;;
;; Vetuma
;;

(defn vetuma! [{:keys [userid firstname lastname] :as data}]
  (->
    (c/get
      (str (server-address) "/dev/api/vetuma")
      {:query-params (select-keys data [:userid :firstname :lastname])})
    decode-response
    :body))

(defn vetuma-stamp! []
  (-> {:userid "123"
       :firstname "Pekka"
       :lastname "Banaani"}
    vetuma!
    :stamp))
