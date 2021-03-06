(ns lupapalvelu.i18n
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error errorf fatal]]
            [clj-time.format :as timef]
            [clj-time.core :as time]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [cheshire.core :as json]
            [ontodev.excel :as xls]
            [sade.core :refer :all]
            [sade.env :as env]
            [sade.strings :as ss]
            [sade.util :as util]
            [lupapiste-commons.i18n.core :as commons]
            [lupapiste-commons.i18n.resources :as commons-resources]))

(def supported-langs [:fi :sv])
(def default-lang (first supported-langs))

(defn- add-term [k t result lang]
  (assoc-in result [(keyword lang) k] (s/trim t)))

(defn- process-translation [row k default-t result lang]
  (let [t (get row lang)]
    (if (seq t)
      (add-term k t result lang)
      (add-term k default-t result lang))))

(defn- process-row [langs-but-default result row]
  (let [k (get row "key")
        default-t (get row (name default-lang))]
    (if (and (not (s/blank? k)) default-t)
      (reduce
        (partial process-translation row k default-t)
        (add-term k default-t result default-lang)
        langs-but-default)
      result)))

(defn- read-sheet [headers sheet]
  (->> sheet seq rest (map xls/read-row) (map (partial zipmap headers))))

(defn- read-translations-txt [name-or-file]
  (let [resource (if (instance? java.io.File name-or-file)
                   name-or-file
                   (io/resource name-or-file))]
    (commons/keys-by-language (commons/read-translations resource))))

(defn i18n-localizations
  "Reads all .txt files from i18n/ resource path.
   Returns them as collection of translation maps, where key is language
   and value is map of loc-key - loc-value pairs"
  []
  (let [this-path (util/this-jar lupapalvelu.main)
        i18n-files (if (ss/ends-with this-path ".jar")      ; are we inside jar
                     (filter #(ss/ends-with % ".txt") (util/list-jar this-path "i18n/"))
                     (util/get-files-by-regex "resources/i18n/" #".+\.txt$")) ; dev
        i18n-files (if (every? string? i18n-files)          ; from jar, filenames are strings
                     (map (partial str "i18n/") i18n-files)
                     i18n-files)]
    (map read-translations-txt i18n-files)))

(defn- load-translations []
  (apply merge-with conj
         (read-translations-txt "shared_translations.txt")
         (i18n-localizations)))

(def- localizations (atom nil))
(def- excel-data (util/future* (load-translations)))

(defn reload! []
  (if (seq @localizations)
    (reset! localizations (load-translations))
    (reset! localizations @excel-data)))

(defn- get-or-load-localizations []
  (if-not @localizations
    (reload!)
    @localizations))

(defn get-localizations []
  (get-or-load-localizations))

(def languages (-> (get-localizations) keys set))

(defn get-terms
  "Return localization terms for given language. If language is not supported returns terms for default language (\"fi\")"
  [lang]
  (let [terms (get-localizations)]
    (or (terms (keyword lang)) (terms default-lang))))

(defn unknown-term [term]
  (if (env/dev-mode?)
    (str "???" term "???")
    (do
      (errorf "unknown localization term '%s'" term)
      "")))

(defn has-term? [lang & terms]
  (not (nil? (get (get-terms (keyword lang)) (s/join \. terms)))))

(defn localize [lang & terms]
  (let [term (s/join \. (map name terms))]
    (if-let [result (get (get-terms (keyword lang)) term)]
      result
      (unknown-term term))))

(defn localizer [lang]
  (partial localize (keyword lang)))

(def ^:dynamic *lang* nil)
(def ^{:doc "Function that localizes provided term using the current language. Use within the \"with-lang\" block."
       :dynamic true}
  loc
  (fn [& args] (throw (Exception. (str "loc called outside with-lang context, args: " args)))))

(defmacro with-lang [lang & body]
  `(binding [*lang* (keyword ~lang)
             loc (localizer ~lang)]
     ~@body))

(defn lang-middleware [handler]
  (fn [request]
    (let [lang (or (get-in request [:params :lang])
                   (get-in request [:user :lang])
                   (name default-lang))]
      (with-lang lang
        (handler request)))))

(defn read-lines [lines]
  (reduce (fn [m line]
            (if-let [[_ k v] (re-matches #"^(.[^\s]*):\s*(.*)$" line)]
              (assoc m (s/trim k) (s/trim v))
              m))
    {}
    lines))

(defn missing-localizations-excel
  "Writes missing localizations to excel file.
   If file is not provided, will create the file to user home dir."
  ([]
   (let [date-str (timef/unparse (timef/formatter "yyyyMMdd") (time/now))
         filename (str (System/getProperty "user.home")
                       "/lupapiste_translations_"
                       date-str
                       ".xlsx")]
        (missing-localizations-excel (io/file filename))))
  ([file]
    (let [i18n-files   (util/get-files-by-regex (io/resource "i18n/") #".+\.txt$")
          loc-maps     (map commons-resources/txt->map i18n-files)
          langs        (distinct (apply concat (map :languages loc-maps)))
          translations (apply merge-with conj (map :translations loc-maps))
          loc-map      {:languages langs :translations translations}]
    (commons-resources/write-excel
      (commons-resources/missing-translations loc-map)
      file))))

(defn excel-to-txt
  "Reads translation excel and generates corresponding txt files (one
  for each sheet) into the same folder. For example,
  lupapiste_translations_20160204.xlsx -> translations.txt
  where translations is the name of the sheet in the excel."
  [xlsx-path]
  (let [file (io/file xlsx-path)
        dir (.getParent file)]
    (with-open [in (io/input-stream file)]
      (let [wb      (xls/load-workbook in)
            sheets  (seq wb)]
        (doseq [sheet sheets
               :let [sheet-name (.getSheetName sheet)]]
         (commons-resources/write-txt
          (commons-resources/sheet->map sheet)
          (io/file dir (str sheet-name ".txt"))))))))
