(ns lupapalvelu.proxy-services-stest
  (:require [lupapalvelu.proxy-services :refer :all]
            [lupapalvelu.itest-util :refer :all]
            [midje.sweet :refer :all]
            [lupapalvelu.wfs :as wfs]
            [lupapalvelu.mongo :as mongo]
            [sade.core :refer [now]]
            [sade.http :as http]
            [sade.env :as env]
            [cheshire.core :as json]))

; make sure proxies are enabled:
(http/post (str (server-address) "/api/proxy-ctrl/on"))

(defn- proxy-request [apikey proxy-name & args]
  (-> (http/post
        (str (server-address) "/proxy/" (name proxy-name))
        {:headers {"authorization" (str "apikey=" apikey)
                   "content-type" "application/json;charset=utf-8"}
         :socket-timeout 10000
         :conn-timeout 10000
         :body (json/encode (apply hash-map args))})
    decode-response
    :body))

(facts "find-addresses-proxy"
  (let [r (proxy-request mikko :find-address :term "piiriniitynkatu 9, tampere")]
    (fact r =contains=> [{:kind "address"
                          :type "street-number-city"
                          :street "Piiriniitynkatu"
                          :number "9"
                          :municipality "837"
                          :name {:fi "Tampere" :sv "Tammerfors"}}])
    (fact (-> r first :location keys) => (just #{:x :y})))
  (let [r (proxy-request mikko :find-address :term "piiriniitynkatu")]
    (fact r =contains=> [{:kind "address"
                          :type "street"
                          :street "Piiriniitynkatu"
                          :number "1"
                          :name {:fi "Tampere" :sv "Tammerfors"}
                          :municipality "837"}])
    (fact (-> r first :location keys) => (just #{:x :y})))
  (let [response (get-addresses-proxy {:params {:query "piiriniitynkatu 9, tampere"}})
        r (json/decode (:body response) true)]
    (fact (:query r) => "piiriniitynkatu 9, tampere")
    (fact (:suggestions r) => ["Piiriniitynkatu 9, Tampere"])
    (fact (:data r) =contains=> [{:street "Piiriniitynkatu",
                                  :number "9",
                                  :name {:fi "Tampere" :sv "Tammerfors"}
                                  :municipality "837"}])
    (fact (-> r :data first :location keys) => (just #{:x :y})))
  (let [response (get-addresses-proxy {:params {:query "piiriniitynkatu 19, tampere"}})
        r (json/decode (:body response) true)]
    (fact (:query r) => "piiriniitynkatu 19, tampere")
    (fact (:suggestions r) => ["Piiriniitynkatu 19, Tampere"])
    (fact (:data r) =contains=> [{:street "Piiriniitynkatu",
                                  :number "19",
                                  :name {:fi "Tampere" :sv "Tammerfors"}
                                  :municipality "837"}])
    (fact (-> r :data first :location keys) => (just #{:x :y}))))

(facts "point-by-property-id"
  (let [property-id "09100200990013"
        request {:params {:property-id property-id}}
        response (point-by-property-id-proxy request)]
    (fact (get-in response [:headers "Content-Type"]) => "application/json; charset=utf-8")
    (let [body (json/decode (:body response) true)
          data (:data body)]
      (fact data => vector?)
      (fact (count data) => 1)
      (fact (keys (first data)) => (just #{:x :y})))))

(facts "property-id-by-point"
  (let [x 385648
        y 6672157
        request {:params {:x x :y y}}
        response (property-id-by-point-proxy request)]
    (fact (get-in response [:headers "Content-Type"]) => "application/json; charset=utf-8")
    (let [body (json/decode (:body response) true)]
      (fact body => "09100200990013"))))

(facts "address-by-point - street number not null"
  (let [x 333168
        y 6822000
        request {:params {:x x :y y}}
        response (address-by-point-proxy request)]
    (fact (get-in response [:headers "Content-Type"]) => "application/json; charset=utf-8")
    (let [body (json/decode (:body response) true)]
      (fact (:street body) => "Luhtaankatu")
      (fact (:number body) => #"\d")
      (fact (:fi (:name body)) => "Tampere"))))

(facts "address-by-point - street number null"
  (let [x 403827.289
        y 6694204.426
        request {:params {:x x :y y}}
        response (address-by-point-proxy request)]
    (fact (get-in response [:headers "Content-Type"]) => "application/json; charset=utf-8")
    (let [body (json/decode (:body response) true)]
      (fact (:street body) => "Kirkkomaanpolku")
      (fact (:number body) => #"\d")
      (fact (:fi (:name body)) => "Sipoo"))))

(facts "plan-urls-by-point-proxy"

  (fact "Helsinki"
   (let [response (plan-urls-by-point-proxy {:params {:x "395628" :y "6677704" :municipality "091"}})
         body     (json/decode (:body response) true)]
     (first body) => {:id "8755"
                      :kuntanro "91"
                      :kaavanro "8755"
                      :vahvistett_pvm "19.12.1985"
                      :linkki "http://img.sito.fi/kaavamaaraykset/91/8755.pdf"
                      :type "sito"}))

  (fact "Mikkeli"
    (let [response (plan-urls-by-point-proxy {:params {:x "533257.514" :y "6828489.823" :municipality "491"}})
          body (json/decode (:body response) true)]

      (first body) => {:id "1436"
                       :kaavanro "12891"
                       :kaavalaji "RKM"
                       :kasitt_pvm "3/31/1989 12:00:00 AM"
                       :linkki "http://194.111.49.141/asemakaavapdf/12891.pdf"
                       :type "bentley"}

      (second body) => {:id "1440"
                        :kaavanro "12021"
                        :kaavalaji "RK"
                        :kasitt_pvm "6/1/1984 12:00:00 AM"
                        :linkki "http://194.111.49.141/asemakaavapdf/12021.pdf"
                        :type "bentley"})))

(facts "general-plan-urls-by-point-proxy"

  (fact "Helsinki"
    (let [response (general-plan-urls-by-point-proxy {:params {:x "395628" :y "6677704"}})
          body (json/decode (:body response) true)]
      (first body) => {:id "0912007"
                       :nimi "Helsingin maanalainen kaava"
                       :pvm "2010-12-08"
                       :tyyppi "Kunnan hyv\u00e4ksym\u00e4"
                       :oikeusvaik "Oikeusvaikutteinen"
                       :lisatieto ""
                       :linkki "http://liiteri.ymparisto.fi//maarays/0912007x.pdf"
                       :type "yleiskaava"}
      (second body) => {:id "0911001"
                        :nimi "Helsingin yleiskaava 2002"
                        :pvm "2003-11-26"
                        :tyyppi "Kunnan hyv\u00e4ksym\u00e4"
                        :oikeusvaik "Oikeusvaikutteinen"
                        :lisatieto "Kaupungin toimittamasta aineistosta puuttuu etel\u00e4inen eli merellinen osa"
                        :linkki "http://liiteri.ymparisto.fi//maarays/0911001x.pdf"
                        :type "yleiskaava"})))

(facts "geoserver-layers"
  (let [base-params {"FORMAT" "image/png"
                     "SERVICE" "WMS"
                     "VERSION" "1.1.1"
                     "REQUEST" "GetMap"
                     "STYLES"  ""
                     "SRS"     "EPSG:3067"
                     "BBOX"   "444416,6666496,444672,6666752"
                     "WIDTH"   "256"
                     "HEIGHT" "256"}]
    (doseq [layer [{"LAYERS" "lupapiste:491_asemakaava"
                    "BBOX"   "512000,6837760,514560,6840320"}
                   {"LAYERS" "lupapiste:109_asemakaava"
                    "BBOX"   "358400,6758400,409600,6809600"}
                   {"LAYERS" "lupapiste:109_kantakartta"
                    "BBOX"   "358400,6758400,409600,6809600"}
                   {"LAYERS" "lupapiste:Naantali_Asemakaavayhdistelma_Velkua"
                    "BBOX"   "208384,6715136,208640,6715392"}
                   {"LAYERS" "lupapiste:Naantali_Asemakaavayhdistelma_Naantali"
                    "BBOX"   "226816,6713856,227328,6714368"}]]
      (let [request {:query-params (merge base-params layer)
                     :headers {"accept-encoding" "gzip, deflate"}
                     :as :stream}]
        (println "Checking" (get layer "LAYERS"))
        (http/get (env/value :maps :geoserver) request) => http200?))))

(facts "WMTS layers"
  (let [base-params {:FORMAT "image/png"
                     :SERVICE "WMTS"
                     :VERSION "1.0.0"
                     :REQUEST "GetTile"
                     :STYLE "default"
                     :TILEMATRIXSET "ETRS-TM35FIN"
                     :TILEMATRIX "11"
                     :TILEROW "1247"
                     :TILECOL "891"}]
    (doseq [layer [{:LAYER "taustakartta"}
                   {:LAYER "kiinteistojaotus"}
                   {:LAYER "kiinteistotunnukset"}]]
      (let [request {:params (merge base-params layer)
                     :headers {"accept-encoding" "gzip, deflate"}}]
        (println "Checking" (get layer :LAYER))
        (wfs/raster-images request "wmts") => http200?))))

(facts "WMS layers"
  (let [base-params {"FORMAT" "image/png"
                     "SERVICE" "WMS"
                     "VERSION" "1.1.1"
                     "REQUEST" "GetMap"
                     "STYLES"  ""
                     "SRS"     "EPSG:3067"
                     "BBOX"   "444416,6666496,444672,6666752"
                     "WIDTH"   "256"
                     "HEIGHT" "256"}]
    (doseq [layer [{"LAYERS" "taustakartta_5k"}
                   {"LAYERS" "taustakartta_10k"}
                   {"LAYERS" "taustakartta_20k"}
                   {"LAYERS" "taustakartta_40k"}
                   {"LAYERS" "ktj_kiinteistorajat" "TRANSPARENT" "TRUE"}
                   {"LAYERS" "ktj_kiinteistotunnukset" "TRANSPARENT" "TRUE"}
                   {"LAYERS" "yleiskaava"}
                   {"LAYERS" "yleiskaava_poikkeavat"}]]
      (let [request {:params (merge base-params layer)
                     :headers {"accept-encoding" "gzip, deflate"}}]
        (println "Checking" (get layer "LAYERS"))
        (wfs/raster-images request "wms") => http200?))))

(fact "WMS capabilites"
  (http/get (str (server-address) "/proxy/wmscap")
    {:query-params {:v (str (now))}
     :throw-exceptions false}) => http200?)

(fact "General plan documents"
  (let [request {:params {:id "0911001"}
                 :headers {"accept-encoding" "gzip, deflate"}}]
    (println "Checking plandocument 0911001")
    (wfs/raster-images request "plandocument") => http200?))
