(ns sade.email
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error fatal]]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [postal.core :as postal]
            [sade.env :as env]
            [sade.strings :as ss]
            [net.cgrand.enlive-html :as enlive]
            [endophile.core :as endophile]
            [clostache.parser :as clostache]
            [net.cgrand.enlive-html :as html]))

;;
;; Default headers:
;; ----------------

(def defaults {:from     "\"Lupapiste\" <lupapiste@lupapiste.fi>"
               :reply-to "\"Lupapiste\" <lupapiste@lupapiste.fi>"})

;;
;; Delivery:
;; ---------
;;

(def deliver-email (fn [to subject body]
                     (assert to "must provide 'to'")
                     (assert subject "must provide 'subject'")
                     (assert body "must provide 'body'")
                     (let [config     (:email (env/get-config))
                           error      (postal/send-message
                                        config
                                        (merge defaults (dissoc config :dummy-server :host :port) {:to to :subject subject :body body}))]
                       (when-not (= (:error error) :SUCCESS)
                         error))))

;;
;; Sending 'raw' email messages:
;; =============================
;;

(defn send-mail
  "Send raw email message. Consider using send-email-message instead."
  [to subject & {:keys [plain html]}]
  (assert (or plain html) "must provide some content")
  (let [plain-body (when plain {:content plain :type "text/plain; charset=utf-8"})
        html-body  (when html {:content html :type "text/html; charset=utf-8"})
        body       (if (and plain-body html-body)
                     [:alternative plain-body html-body]
                     [(or plain-body html-body)])]
    (deliver-email to subject body)))

;;
;; Sending emails with templates:
;; ==============================
;;

(declare apply-template)

(defn send-email-message
  "Sends email message using a template."
  [to subject template context]
  (assert (and to subject template context) "missing argument")
  (let [[plain html] (apply-template template context)]
    (send-mail to subject :plain plain :html html)))

;;
;; templating:
;; -----------
;;

(defn find-resource [resource-name]
  (or (io/resource (str "email-templates/" resource-name)) (throw (IllegalArgumentException. (str "Can't find mail resource: " resource-name)))))

(defn fetch-template [template-name]
  (with-open [in (io/input-stream (find-resource template-name))]
    (slurp in)))

(defn fetch-html-template [template-name]
  (enlive/html-resource (find-resource template-name)))

(when-not (env/dev-mode?)
  (alter-var-root #'fetch-template memoize)
  (alter-var-root #'fetch-html-template memoize))

;;
;; Plain text support:
;; -------------------

(defmulti ->str (fn [element] (if (map? element) (:tag element) :str)))

(defn- ->str* [elements] (s/join (map ->str elements)))

(defmethod ->str :default [element] (->str* (:content element)))
(defmethod ->str :str     [element] (s/join element))
(defmethod ->str :h1      [element] (str \newline (->str* (:content element)) \newline))
(defmethod ->str :h2      [element] (str \newline (->str* (:content element)) \newline))
(defmethod ->str :p       [element] (str \newline (->str* (:content element)) \newline))
(defmethod ->str :ul      [element] (->str* (:content element)))
(defmethod ->str :li      [element] (str \* \space (->str* (:content element)) \newline))
(defmethod ->str :a       [element] (str (->str* (:content element)) \: \space (get-in element [:attrs :href])))
(defmethod ->str :img     [element] "")
(defmethod ->str :br      [element] "")
(defmethod ->str :hr      [element] "\n---------\n")
(defmethod ->str :blockquote [element] (-> element :content ->str* (s/replace #"\n{2,}" "\n  ") (s/replace #"^\n" "  ")))

;;
;; HTML support:
;; -------------

(defn- wrap-html [html-body]
  (let [html-wrap (fetch-html-template "html-wrap.html")]
    (enlive/transform html-wrap [:body] (enlive/content html-body))))

;;
;; Apply template:
;; ---------------

(defn apply-md-template [template context]
  (let [master      (fetch-template "master.md")
        header      (fetch-template "header.md")
        body        (fetch-template template)
        footer      (fetch-template "footer.md")
        html-wrap   (fetch-html-template "html-wrap.html")
        rendered    (clostache/render master context {:header header :body body :footer footer})
        content     (endophile/to-clj (endophile/mp rendered))]
    [(->str* content)
     (->> content enlive/content (enlive/transform html-wrap [:body]) endophile/html-string)]))

(defn apply-html-template [template context]
  #_(let [master    (html/html-resource (fetch-template "master.html"))
        body      (fetch-template template)
        rendered  (clostache/render master context {:header header :body body :footer footer})
        content   (endophile/to-clj (endophile/mp rendered))]
    [(->str* content) (endophile/html-string (wrap-html content))]))

(defn apply-template [template context]
  (cond
    (ss/ends-with template ".md")    (apply-md-template template context)
    (ss/ends-with template ".html")  (apply-html-template template context)
    :else                            (throw (Exception. (str "unsupported template: " template)))))

