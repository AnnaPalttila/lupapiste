(ns lupapalvelu.token
  (:require [taoensso.timbre :as timbre :refer [errorf]]
            [monger.operators :refer :all]
            [noir.request :as request]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.user :as user]
            [lupapalvelu.security :as security]
            [sade.core :refer :all]))

(defmulti handle-token (fn [token-data params] (:token-type token-data)))

(defmethod handle-token :default [token-data params]
  (errorf "consumed token: token-type %s does not have handler: id=%s" (:token-type token-data) (:_id token-data)))

(def- default-ttl (* 24 60 60 1000))

(def make-token-id (partial security/random-password 48))

(defn make-token [token-type user data & {:keys [ttl auto-consume] :or {ttl default-ttl auto-consume true}}]
  (let [token-id (make-token-id)
        now (now)
        request (request/ring-request)]
    (mongo/insert :token {:_id token-id
                          :token-type token-type
                          :data data
                          :used nil
                          :auto-consume auto-consume
                          :created now
                          :expires (+ now ttl)
                          :ip (get-in request [:headers "x-real-ip"] (:remote-addr request))
                          :user-id (:id (or user (user/current-user request)))})
    token-id))

(defn get-token [id & {:keys [consume] :or {consume false}}]
  (when-let [token (mongo/select-one :token {:_id id
                                             :used nil
                                             :expires {$gt (now)}})]
    (when (or consume (:auto-consume token))
      (mongo/update-by-id :token id {$set {:used (now)}}))
    (update-in token [:token-type] keyword)))

(defn consume-token [id params & {:keys [consume] :or {consume false}}]
  (when-let [token-data (get-token id :consume consume)]
    (handle-token token-data params)))
