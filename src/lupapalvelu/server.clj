(ns lupapalvelu.server
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error fatal tracef debugf infof warnf errorf fatalf]]
            [noir.server :as server]
            [lupapalvelu.logging]
            [lupapalvelu.web :as web]
            [lupapalvelu.vetuma]
            [sade.env :as env]
            [sade.security-headers :as headers]
            [sade.dummy-email-server]
            [lupapalvelu.fixture :as fixture]
            [lupapalvelu.fixture.kind]
            [lupapalvelu.fixture.minimal]
            [lupapalvelu.fixture.municipality-test-users]
            [lupapalvelu.fixture.finland-rakval]
            [lupapalvelu.fixture.cgi-test-users]
            [lupapalvelu.application]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.document.commands]
            [lupapalvelu.user-api]
            [lupapalvelu.operations]
            [lupapalvelu.statement-api]
            [lupapalvelu.proxy-services]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.ua-compatible-header :as uach]
            [lupapalvelu.etag :as etag]
            [lupapalvelu.document.schema-repository]
            [lupapalvelu.common-actions]
            [lupapalvelu.migration.migration :as migration]
            [lupapalvelu.screenmessage]))

(defn -main [& _]
  (infof "Server starting in %s mode" env/mode)
  (infof "Running on %s version %s (%s) [%s], trustStore is %s"
    (System/getProperty "java.vm.name")
    (System/getProperty "java.runtime.version")
    (System/getProperty "java.vm.info")
    (if (java.awt.GraphicsEnvironment/isHeadless) "headless" "headful")
    (System/getProperty "javax.net.ssl.trustStore"))
  (info "Running on Clojure" (clojure-version))
  (mongo/connect!)
  (migration/update!)
  (mongo/ensure-indexes)
  (server/add-middleware web/tempfile-cleanup)
  (server/add-middleware i18n/lang-middleware)
  (server/add-middleware web/parse-json-body-middleware)
  (server/add-middleware uach/add-ua-compatible-header)
  (server/add-middleware headers/session-id-to-mdc)
  (server/add-middleware headers/add-security-headers)
  (server/add-middleware web/anti-csrf)
  (server/add-middleware web/authentication)
  (server/add-middleware web/session-timeout)
  (server/add-middleware etag/if-none-match-build-number)
  (env/in-dev
    (warn "*** Instrumenting performance monitoring")
    (require 'lupapalvelu.perf-mon)
    ((resolve 'lupapalvelu.perf-mon/init)))
  (when (env/feature? :nrepl)
    (warn "*** Starting nrepl")
    (require 'clojure.tools.nrepl.server)
    ((resolve 'clojure.tools.nrepl.server/start-server) :port 9090))
  (let [jetty-opts (into
                     {:max-threads 250}
                     (when (env/dev-mode?)
                       {:ssl? true
                        :ssl-port 8443
                        :keystore "./keystore"
                        :key-password "lupapiste"}))]
    (server/start env/port {:mode env/mode
                            :ns 'lupapalvelu.web
                            :jetty-options jetty-opts
                            :session-cookie-attrs (env/value :cookie)}))
  "server running")

"server ready to start"
