(ns lupapalvelu.singlepage
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error fatal tracef debugf infof warnf errorf fatalf]]
            [clojure.java.io :as io]
            [lupapalvelu.components.ui-components :refer [ui-components] :as uic]
            [net.cgrand.enlive-html :as enlive]
            [clj-time.coerce :as tc]
            [sade.env :as env]
            [sade.strings :as ss]
            [sade.core :refer :all]
            [lupapalvelu.components.core :as c])
  (:import [java.io ByteArrayOutputStream ByteArrayInputStream]
           [java.util.zip GZIPOutputStream]
           [org.apache.commons.io IOUtils]
           [com.googlecode.htmlcompressor.compressor HtmlCompressor]
           [com.yahoo.platform.yui.compressor JavaScriptCompressor CssCompressor]
           [org.mozilla.javascript ErrorReporter EvaluatorException]))

(defn write-header [kind out n]
  (when (env/feature? :no-minification)
    (.write out (format "\n\n/*\n * %s\n */\n" n)))
  (when (= kind :js)
    (.write out "\n;\n\n"))
  out)

(def error-reporter
  (reify ErrorReporter
    (^void warning [this ^String message, ^String sourceName,
                    ^int line, ^String lineSource, ^int lineOffset]
      (warn message))
    (^void error [this ^String message, ^String sourceName,
                    ^int line, ^String lineSource, ^int lineOffset]
      (error message))
    (^EvaluatorException runtimeError [this ^String message, ^String sourceName,
                    ^int line, ^String lineSource, ^int lineOffset]
      (error message) (EvaluatorException. message))))

(defn- minified [kind ^java.io.Reader in ^java.io.Writer out]
  (cond
    (env/feature? :no-minification) (IOUtils/copy in out)
    (= kind :js) (let [c (JavaScriptCompressor. in error-reporter)]
                   ; no linebreaks, obfuscate locals, no verbose,
                   (.compress c out -1 true false;
                     ; preserve semicolons, disable optimizations
                     true true))
    (= kind :css) (let [c (CssCompressor. in)]
                    ; no linebreaks
                    (.compress c out -1))))

(defn- fn-name [f]
  (-> f str (.replace \$ \/) (.split "@") first))

(defn compose-resource [kind component]
  (let [stream (ByteArrayOutputStream.)]
    (with-open [out (io/writer stream)]
      (doseq [src (c/get-resources ui-components kind component)]
        (if (fn? src)
          (.write (write-header kind out (str "fn: " (fn-name src))) (src))
          (with-open [in (-> src c/path io/resource io/input-stream io/reader)]
            (if (or (ss/contains? src "debug") (ss/contains? src ".min."))
              (IOUtils/copy in (write-header kind out src))
              (minified kind in (write-header kind out src)))))))
    (.toByteArray stream)))

(defn parse-html-resource [c resource]
  (let [h (enlive/html-resource resource)]
    (assoc c
      :nav    (concat (:nav c)    (enlive/select h [:nav]))
      :info   (concat (:info c)   (enlive/select h [:div.notification]))
      :footer (concat (:footer c) (enlive/select h [:footer]))
      :page   (concat (:page  c)  (enlive/select h [:section.page])))))

(defn- resource-url [component kind]
  (str (kind (env/value :cdn)) (:build-number env/buildinfo) "/" (name component) "." (name kind)))

(def- buildinfo-summary
  (format "%s %s [%s] %4$tF %4$tT (%5$s)"
          env/target-env
          (:hg-branch env/buildinfo)
          (name env/mode)
          (tc/to-date (tc/from-long (:time env/buildinfo)))
          (:build-number env/buildinfo)))

(defn ie-main-css-fallback [template c]
  {:pre [(> c 1)]}
  (let [main-css-suffices (drop 2 (range (+ c 1)))
        link-str (apply str (map
                              #(str "<link href=\"/lp-static/css/main_" % ".css?" (:build-number env/buildinfo) "\" rel=\"stylesheet\"></link>\n")
                              main-css-suffices))
        fallback-str (format "<!--[if lte IE 9]>%s<![endif]-->" link-str)
        fallback-elements (enlive/html-snippet fallback-str)]
    (enlive/at
      template
      [:head :link#inject-ie-css]
      (enlive/substitute fallback-elements))))

(defn inject-content [t {:keys [nav info page footer]} component]
  (let [main-css-count (uic/main-css-count)
        transformed (-> t
                        (enlive/transform [:body] (fn [e] (assoc-in e [:attrs :class] (name component))))
                        (enlive/transform [:nav] (enlive/content (map :content nav)))
                        (enlive/transform [:div.notification] (enlive/content (map :content info)))
                        (enlive/transform [:section] (enlive/content page))
                        (enlive/transform [:footer] (enlive/content (map :content footer)))
                        (enlive/transform [:script] (fn [e] (if (= (-> e :attrs :src) "inject-common") (assoc-in e [:attrs :src] (resource-url :common :js)) e)))
                        (enlive/transform [:script] (fn [e] (if (= (-> e :attrs :src) "inject-app") (assoc-in e [:attrs :src] (resource-url component :js)) e)))
                        (enlive/transform [:link] (fn [e] (if (= (-> e :attrs :href) "inject") (assoc-in e [:attrs :href] (resource-url component :css)) e)))
                        (enlive/transform [:#buildinfo] (enlive/content buildinfo-summary)))]
    (if (> main-css-count 1)
      (enlive/emit* (ie-main-css-fallback transformed main-css-count))
      (enlive/emit* transformed))))

(defn- compress-html [^String html]
  (let [c (doto (HtmlCompressor.)
            (.setRemoveScriptAttributes true)    ; remove optional attributes from script tags
            (.setRemoveStyleAttributes true)     ; remove optional attributes from style tags
            (.setRemoveLinkAttributes true)      ; remove optional attributes from link tags
            (.setRemoveFormAttributes true)      ; remove optional attributes from form tags
            (.setSimpleBooleanAttributes true)   ; remove values from boolean tag attributes
            (.setRemoveJavaScriptProtocol true)  ; remove "javascript:" from inline event handlers
            (.setRemoveHttpProtocol false)       ; do not replace "http://" with "//" inside tag attributes
            (.setRemoveHttpsProtocol false)      ; do not replace "https://" with "//" inside tag attributes
            (.setRemoveSurroundingSpaces HtmlCompressor/BLOCK_TAGS_MAX)  ; remove spaces around provided tags
            (.setPreservePatterns [(re-pattern "<!--\\s*/?ko.*-->")]))] ; preserve KnockoutJS comments
    (.compress c html)))

(defn compose-html [component]
  (let [out (ByteArrayOutputStream.)]
    (doseq [element (inject-content
                      (enlive/html-resource (c/path "template.html"))
                      (reduce parse-html-resource {} (map (partial str (c/path)) (c/get-resources ui-components :html component)))
                      component)]
      (.write out (ss/utf8-bytes element)))
    (-> out (.toString (.name ss/utf8)) (compress-html) (ss/utf8-bytes))))

(defn compose [kind component]
  (tracef "Compose %s%s" component kind)
  (if (= :html kind)
    (compose-html component)
    (compose-resource kind component)))
