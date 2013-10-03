(ns lupapalvelu.proxy-services
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error fatal]]
            [clj-http.client :as client]
            [noir.response :as resp]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.string :as s]
            [lupapalvelu.wfs :as wfs]
            [lupapalvelu.find-address :as find-address]
            [clojure.data.zip.xml :refer :all]
            [sade.util :refer [dissoc-in select]]))

;;
;; NLS:
;;

(defn- trim [s]
  (when-not (s/blank? s) (s/trim s)))

(defn- parse-address [query]
  (let [[[_ street number city]] (re-seq #"([^,\d]+)\s*(\d+)?\s*(?:,\s*(.+))?" query)
        street (trim street)
        city (trim city)]
    [street number city]))

(defn get-addresses [street number city]
  (wfs/post wfs/maasto
    (wfs/query {"typeName" "oso:Osoitenimi"}
      (wfs/sort-by ["oso:katunumero"])
      (wfs/filter
        (wfs/and
          (wfs/property-is-like "oso:katunimi"     street)
          (wfs/property-is-like "oso:katunumero"   number)
          (wfs/or
            (wfs/property-is-like "oso:kuntanimiFin" city)
            (wfs/property-is-like "oso:kuntanimiSwe" city)))))))

(defn get-addresses-proxy [request]
  (let [query (get (:params request) :query)
        address (parse-address query)
        response (apply get-addresses address)]
    (if response
      (let [features (take 10 response)]
        (resp/json {:query query
                    :suggestions (map wfs/feature-to-simple-address-string features)
                    :data (map wfs/feature-to-address features)}))
      (resp/status 503 "Service temporarily unavailable"))))

(defn find-addresses-proxy [request]
  (let [term (get (:params request) :term)]
    (if (string? term)
      (resp/json (or (find-address/search term) []))
      (resp/status 400 "Missing query param 'term'"))))

(defn point-by-property-id-proxy [request]
  (let [property-id (get (:params request) :property-id)
        features (wfs/point-by-property-id property-id)]
    (if features
      (resp/json {:data (map wfs/feature-to-position features)})
      (resp/status 503 "Service temporarily unavailable"))))

(defn property-id-by-point-proxy [request]
  (let [{x :x y :y} (:params request)
        features (wfs/property-id-by-point x y)]
    (if features
      (resp/json (:kiinttunnus (wfs/feature-to-property-id (first features))))
      (resp/status 503 "Service temporarily unavailable"))))

(defn address-by-point-proxy [request]
  (let [{x :x y :y} (:params request)
        features (wfs/address-by-point x y)]
    (if features
      (resp/json (wfs/feature-to-address-details (first features)))
      (resp/status 503 "Service temporarily unavailable"))))

;
; Utils:
;

(defn- secure
  "Takes a service function as an argument and returns a proxy function that invokes the original
  function. Proxy function returns what ever the service function returns, excluding some unsafe
  stuff. At the moment strips the 'Set-Cookie' headers."
  [f]
  (fn [request]
    (let [response (f request)]
      (assoc response :headers (dissoc (:headers response) "set-cookie" "server")))))

(defn- cache [max-age-in-s f]
  (let [cache-control {"Cache-Control" (str "public, max-age=" max-age-in-s)}]
    (fn [request]
      (let [response (f request)]
        (assoc response :headers (merge (:headers response) cache-control))))))

;;
;; Proxy services by name:
;;

(def services {"point-by-property-id" point-by-property-id-proxy
               "property-id-by-point" property-id-by-point-proxy
               "address-by-point" address-by-point-proxy
               "find-address" find-addresses-proxy
               "get-address" get-addresses-proxy})
