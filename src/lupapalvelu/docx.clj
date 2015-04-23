(ns lupapalvelu.docx
  (:require [taoensso.timbre :as timbre :refer [debug debugf info warn error]]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [sade.core :refer :all]
            [sade.util :as util])
  (:import [java.io OutputStream InputStream ByteArrayOutputStream ByteArrayInputStream]
           [fr.opensagres.xdocreport.converter Options ConverterTypeTo ConverterTypeVia]
           [fr.opensagres.xdocreport.document IXDocReport]
           [fr.opensagres.xdocreport.document.registry XDocReportRegistry]
           [fr.opensagres.xdocreport.template IContext]
           [fr.opensagres.xdocreport.template TemplateEngineKind]))

(set! *warn-on-reflection* true)

(def ^Options to-pdf (-> (Options/getTo ConverterTypeTo/PDF) (.via ConverterTypeVia/XWPF)))

(defn- freemarker-compliant [m]
  ; Freemarker throws exception from null values by default
  (-> m walk/stringify-keys (util/convert-values #(nil? %2) (constantly ""))))

(defn- ^IContext create-context [^IXDocReport report m]
  (reduce (fn [^IContext c, [k v]] (.put c k v) c) (.createContext report) m))

(def- yritystilisopimus-default-model {:date ""
                                       :company {:name "", :y "", :address1 "", :address2 "", :zip "", :po ""}
                                       :contact {:firstName "", :lastName ""}
                                       :account {:type "", :price ""}})

(defn ^InputStream docx-template-to-pdf [template-name model]
  (with-open [in (-> template-name io/resource io/input-stream)
              out (ByteArrayOutputStream. 4096)]
    (let [report  (.loadReport (XDocReportRegistry/getRegistry) in TemplateEngineKind/Freemarker )
          context (->> model freemarker-compliant (create-context report))
          starting (now)]
      (.convert report context to-pdf out)
      (debugf "Conversion took %d ms" (- (now) starting))
      (ByteArrayInputStream. (.toByteArray out)))))

(defn ^InputStream yritystilisopimus [company contact account timestamp]
  {:pre [(map? company) (map? contact) (map? account)]}
  (let [model (util/deep-merge
                yritystilisopimus-default-model
                {:date (util/to-local-date timestamp)
                 :company company
                 :contact contact
                 :account account})]
    (docx-template-to-pdf "yritystilisopimus.docx" model)))

(defn- poc []
  (with-open [pdf (yritystilisopimus
                    {:name "Asiakas Oy",
                     :y "123456-1"
                     :address1 "Osoiterivi 1"}
                    {:firstName "Etu", :lastName "Suku"}
                    {:type "TEST", :price "100"}
                    (+ (now) (* 1000 60 60 24)))
              out (io/output-stream "out.pdf")]
    (io/copy pdf out)))
