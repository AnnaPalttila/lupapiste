(ns lupapalvelu.proxy-services
  (:require [clojure.data.zip.xml :refer :all]
            [clojure.xml :as xml]
            [taoensso.timbre :as timbre :refer [trace debug info warn error fatal]]
            [noir.response :as resp]
            [sade.coordinate :as coord]
            [sade.env :as env]
            [sade.property :as p]
            [sade.strings :as ss]
            [sade.util :refer [dissoc-in select ->double]]
            [lupapalvelu.find-address :as find-address]
            [lupapalvelu.wfs :as wfs]))

;;
;; NLS:
;;

(defn- trim [s]
  (when-not (ss/blank? s) (ss/trim s)))

(defn- parse-address [query]
  (let [[[_ street number city]] (re-seq #"([^,\d]+)\s*(\d+)?\s*(?:,\s*(.+))?" query)
        street (trim street)
        city (trim city)]
    [street number city]))

(defn get-addresses [street number city]
  (wfs/post wfs/maasto
    (wfs/query {"typeName" "oso:Osoitenimi"}
      (wfs/ogc-sort-by ["oso:katunumero"])
      (wfs/ogc-filter
        (wfs/ogc-and
          (wfs/property-is-like "oso:katunimi"     street)
          (wfs/property-is-like "oso:katunumero"   number)
          (wfs/ogc-or
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
  (let [term (get (:params request) :term)
        term (ss/replace term #"\p{Punct}" " ")]
    (if (string? term)
      (resp/json (or (find-address/search term) []))
      (resp/status 400 "Missing query param 'term'"))))

(defn point-by-property-id-proxy [request]
  (let [property-id (get (:params request) :property-id)
        features (wfs/point-by-property-id property-id)]
    (if features
      (resp/json {:data (map wfs/feature-to-position features)})
      (resp/status 503 "Service temporarily unavailable"))))

(defn area-by-property-id-proxy [{{property-id :property-id} :params :as request}]
  (if (and (string? property-id) (re-matches p/db-property-id-pattern property-id) )
    (let [features (wfs/area-by-property-id property-id)]
      (if features
        (resp/json {:data (map wfs/feature-to-area features)})
        (resp/status 503 "Service temporarily unavailable")))
    (resp/status 400 "Bad Request")))

(defn property-id-by-point-proxy [{{x :x y :y} :params}]
  (if (and (coord/valid-x? x) (coord/valid-y? y))
    (let [features (wfs/property-id-by-point x y)]
      (if features
        (resp/json (:kiinttunnus (wfs/feature-to-property-id (first features))))
        (resp/status 503 "Service temporarily unavailable")))
    (resp/status 400 "Bad Request")))

(defn address-by-point-proxy [{{x :x y :y} :params}]
  (if (and (coord/valid-x? x) (coord/valid-y? y))
    (if-let [features (wfs/address-by-point x y)]
      (resp/json (wfs/feature-to-address-details (first features)))
      (resp/status 503 "Service temporarily unavailable"))
    (resp/status 400 "Bad Request")))

(def wdk-type-pattern #"^POINT|^LINESTRING|^POLYGON")

(defn property-info-by-wkt-proxy [request] ;example: wkt=POINT(404271+6693892)&radius=100
  (let [{wkt :wkt radius :radius :or {wkt ""}} (:params request)
        type (re-find wdk-type-pattern wkt)
        coords (ss/replace wkt wdk-type-pattern "")
        features (case type
                   "POINT" (let [[x y] (ss/split (first (re-find #"\d+(\.\d+)* \d+(\.\d+)*" coords)) #" ")]
                             (if-not (ss/numeric? radius)
                               (wfs/property-info-by-point x y)
                               (wfs/property-info-by-radius x y radius)))
                   "LINESTRING" (wfs/property-info-by-line (ss/split (ss/replace coords #"[\(\)]" "") #","))
                   "POLYGON" (let [outterring (first (ss/split coords #"\)" 1))] ;;; pudotetaan reiat pois
                               (wfs/property-info-by-polygon (ss/split (ss/replace outterring #"[\(\)]" "") #",")))
                   nil)]
    (if features
      (resp/json (map wfs/feature-to-property-info features))
      (resp/status 503 "Service temporarily unavailable"))))

(defn create-layer-object [layer-name]
  (let [layer-category (cond
                         (re-find #"^\d+_asemakaava$" layer-name) "asemakaava"
                         (re-find #"^\d+_kantakartta$" layer-name) "kantakartta"
                         :else "other")
        layer-id (case layer-category
                      "asemakaava" "101"
                      "kantakartta" "102"
                      "0")]
    {:wmsName layer-name
     :wmsUrl "/proxy/wms"
     :name (case layer-category
             "asemakaava" {:fi "Asemakaava (kunta)" :sv "Detaljplan (kommun)" :en "???"}
             "kantakartta" {:fi "Kantakartta" :sv "Baskarta" :en "???"}
             {:fi "" :sv "" :en ""})
     :subtitle (case layer-category
                 "asemakaava" {:fi "Kunnan palveluun toimittama ajantasa-asemakaava" :sv "Detaljplan (kommun)" :en "???"}
                 "kantakartta" {:fi "" :sv "" :en ""}
                 {:fi "" :sv "" :en ""})
     :id layer-id
     :baseLayerId layer-id
     :isBaseLayer (or (= layer-category "asemakaava") (= layer-category "kantakartta"))}))

(defn wms-capabilities-proxy [request]
  (let [{municipality :municipality} (:params request)
        capabilities (wfs/get-our-capabilities)
        layers (wfs/capabilities-to-layers capabilities)]
    (if layers
      (resp/json
        (if (nil? municipality)
          (map create-layer-object (map wfs/layer-to-name layers))
          (filter
            #(and
               (not= "0" (:id %))      ;; TODO: This is quick fix to make service working again.  Find better fix.
               (= (re-find #"^\d+" (:wmsName %)) municipality))
            (map create-layer-object (map wfs/layer-to-name layers)))
          ))
      (resp/status 503 "Service temporarily unavailable"))))

;; The value of "municipality" is "liiteri" when searching from Liiteri and municipality code when searching from municipalities.
(defn plan-urls-by-point-proxy [{{:keys [x y municipality]} :params}]
  (let [municipality (trim municipality)]
    (if (and (coord/valid-x? x) (coord/valid-y? y) (or (= "liiteri" (ss/lower-case municipality)) (ss/numeric? municipality)))
      (let [response (wfs/plan-info-by-point x y municipality)
            k (keyword municipality)
            gfi-mapper (if-let [f-name (env/value :plan-info k :gfi-mapper)]
                         (resolve (symbol f-name))
                         wfs/gfi-to-features-sito)
            feature-mapper (if-let [f-name (env/value :plan-info k :feature-mapper)]
                             (resolve (symbol f-name))
                             wfs/feature-to-feature-info-sito)]
        (if response
          (resp/json (map feature-mapper (gfi-mapper response municipality)))
          (resp/status 503 "Service temporarily unavailable")))
      (resp/status 400 "Bad Request"))))

(defn general-plan-urls-by-point-proxy [{{x :x y :y} :params}]
  (if (and (coord/valid-x? x) (coord/valid-y? y))
    (if-let [response (wfs/general-plan-info-by-point x y)]
      (resp/json (map wfs/general-plan-feature-to-feature-info (wfs/gfi-to-general-plan-features response)))
      (resp/status 503 "Service temporarily unavailable"))
    (resp/status 400 "Bad Request")))

;
; Utils:
;

(defn- secure
  "Takes a service function as an argument and returns a proxy function that invokes the original
  function. Proxy function returns what ever the service function returns, excluding some unsafe
  stuff. At the moment strips the 'Set-Cookie' headers."
  [f service]
  (fn [request]
    (let [response (f request service)]
      (update-in response [:headers] dissoc "set-cookie" "server"))))

(defn- cache [max-age-in-s f]
  (let [cache-control {"Cache-Control" (str "public, max-age=" max-age-in-s)}]
    (fn [request]
      (let [response (f request)]
        (update-in response [:headers] merge cache-control)))))

;;
;; Proxy services by name:
;;

(def services {"nls" (cache (* 3 60 60 24) (secure wfs/raster-images "nls"))
               "wms" (cache (* 3 60 60 24) (secure wfs/raster-images "wms"))
               "wmts/maasto" (cache (* 3 60 60 24) (secure wfs/raster-images "wmts"))
               "wmts/kiinteisto" (cache (* 3 60 60 24) (secure wfs/raster-images "wmts"))
               "point-by-property-id" point-by-property-id-proxy
               "area-by-property-id" area-by-property-id-proxy
               "property-id-by-point" property-id-by-point-proxy
               "address-by-point" address-by-point-proxy
               "find-address" find-addresses-proxy
               "get-address" get-addresses-proxy
               "property-info-by-wkt" property-info-by-wkt-proxy
               "wmscap" wms-capabilities-proxy
               "plan-urls-by-point" plan-urls-by-point-proxy
               "general-plan-urls-by-point" general-plan-urls-by-point-proxy
               "plandocument" (cache (* 3 60 60 24) (secure wfs/raster-images "plandocument"))})

