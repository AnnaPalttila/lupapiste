(ns lupapalvelu.components.ui-components
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error fatal]]
            [lupapalvelu.components.core :as c]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.mime :as mime]
            [lupapalvelu.xml.krysp.validator :as validator]
            [sade.env :as env]
            [sade.util :as util]
            [cheshire.core :as json]
            [lupapalvelu.attachment :refer [attachment-types-osapuoli, attachment-scales, attachment-sizes]]
            [lupapalvelu.attachment-api :refer [post-verdict-states]]))

(def debugjs {:depends [:jquery]
              :js ["debug.js"]
              :name "common"})

(def mockjax {:depends [:jquery]
              :js ["jquery.mockjax.js"]
              :name "jquery"})

(defn- conf []
  (let [js-conf {:maps              (env/value :maps)
                 :fileExtensions    mime/allowed-extensions
                 :passwordMinLength (env/value :password :minlength)
                 :mode              env/mode
                 :build             (:build-number env/buildinfo)
                 :cookie            (env/value :cookie)
                 :wannaJoinUrl      (env/value :oir :wanna-join-url)
                 :userAttachmentTypes (map #(str "osapuolet." (name %)) attachment-types-osapuoli)
                 :attachmentScales  attachment-scales
                 :attachmentSizes   attachment-sizes
                 :postVerdictStates post-verdict-states
                 :stampableMimes    (filter identity (map mime/mime-types lupapalvelu.stamper/file-types))
                 :foremanRoles      (:body (first lupapalvelu.document.schemas/kuntaroolikoodi-tyonjohtaja))
                 :foremanReadonlyFields ["luvanNumero", "katuosoite", "rakennustoimenpide", "kokonaisala"]}]
    (str "var LUPAPISTE = LUPAPISTE || {};LUPAPISTE.config = " (json/generate-string js-conf) ";")))

(defn- loc->js []
  (str ";loc.setTerms(" (json/generate-string (i18n/get-localizations)) ");"))

(defn- schema-versions-by-permit-type []
  (str ";LUPAPISTE.config.kryspVersions = " (json/generate-string validator/supported-versions-by-permit-type) ";"))

(def ui-components
  {;; 3rd party libs
   :cdn-fallback   {:js ["jquery-1.8.3.min.js" "jquery-ui-1.10.2.min.js" "jquery.dataTables.min.js"]}
   :jquery         {:js ["jquery.ba-hashchange.js" "jquery.metadata-2.1.js" "jquery.cookie.js" "jquery.caret.js"]}
   :jquery-upload  {:js ["jquery.ui.widget.js" "jquery.iframe-transport.js" "jquery.fileupload.js"]}
   :knockout       {:js ["knockout-3.2.0.min.js" "knockout.mapping-2.4.1.js" "knockout.validation.min.js" "knockout-repeat-2.0.0.js"]}
   :lo-dash        {:js ["lodash-1.3.1.min.js"]}
   :underscore     {:depends [:lo-dash]
                    :js ["underscore.string.min.js" "underscore.string.init.js"]}
   :moment         {:js ["moment.min.js"]}
   :open-layers    {:js ["openlayers-2.13_20140619.min.lupapiste.js"]}

   ;; Init can also be used as a standalone lib, see web.clj
   :init         {:depends [:underscore]
                  :js [conf "hub.js" "log.js"]}

   ;; Common components

   :debug        (if (env/dev-mode?) debugjs {})

   :mockjax      (if (env/dev-mode?) mockjax {})

   :i18n         {:depends [:jquery :underscore]
                  :js ["loc.js" loc->js]}

   :selectm      {:js ["selectm.js"]}

   :selectm-html {:html ["selectm.html"]
                  :css ["selectm.css"]}

   :expanded-content  {:depends [:jquery]
                       :js ["expanded-content.js"]}

   :common       {:depends [:init :jquery :jquery-upload :knockout :underscore :moment :i18n :selectm
                            :expanded-content :mockjax :open-layers]
                  :js ["util.js" "event.js" "pageutil.js" "notify.js" "ajax.js" "app.js" "nav.js"
                       "ko.init.js" "dialog.js" "datepicker.js" "requestcontext.js" "currentUser.js" "features.js"
                       "statuses.js" "statusmodel.js" "authorization.js" "vetuma.js"]}

   :common-html  {:depends [:selectm-html]
                  :css ["css/main.css" "jquery-ui.css"]
                  :html ["404.html" "footer.html"]}

   ;; Components to be included in a SPA

   :global-models {:js ["application-model.js" "register-models.js"]}

   :screenmessages  {:js   ["screenmessage.js"]
                     :html ["screenmessage.html"]}

   :map          {:depends [:common-html]
                  :js [ "gis.js" "locationsearch.js"]
                  :html ["map-popup.html"]}

   :mypage       {:depends [:common-html]
                  :js ["mypage.js"]
                  :html ["mypage.html"]
                  :css ["mypage.css"]}

   :user-menu     {:html ["nav.html"]}

   :modal-datepicker {:depends [:common-html]
                      :html ["modal-datepicker.html"]
                      :js   ["modal-datepicker.js"]}

   :authenticated {:depends [:screenmessages]
                   :js ["comment.js"]
                   :html ["comments.html"]}

   :invites      {:depends [:common-html]
                  :js ["invites.js"]}

   :repository   {:depends [:common-html]
                  :js ["repository.js"]}

   :tree         {:js ["tree.js"]
                  :html ["tree.html"]
                  :css ["tree.css"]}

   :accordion    {:js ["accordion.js"]
                  :css ["accordion.css"]}

   :signing      {:depends [:common-html]
                  :html ["signing-dialogs.html"]
                  :js ["signing-model.js" "verdict-signing-model.js"]}

   :stamp        {:depends [:common-html]
                  :html ["stamp-template.html"]
                  :js ["stamp-model.js" "stamp.js"]}

   :verdict-attachment-prints {:depends [:common-html]
                               :html ["verdict-attachment-prints-order-template.html"]
                               :js ["verdict-attachment-prints-order-model.js"]}

   :attachment   {:depends [:common-html :repository :application :signing :side-panel]
                  :js ["targeted-attachments-model.js" "attachment.js" "attachmentTypeSelect.js" "attachment-utils.js"]
                  :html ["targetted-attachments-template.html" "attachment.html" "upload.html"]}

   :task         {:depends [:common-html :attachment]
                  :js ["task.js"]
                  :html ["task.html"]}

   :create-task  {:js ["create-task.js"]
                  :html ["create-task.html"]}

   :ui-components {:depends [:common-html]
                   :js ["ui-components.js"
                        "fill-info-button/fill-info-model.js"
                        "foreman-history/foreman-history-model.js"
                        "foreman-other-applications/foreman-other-applications-model.js"
                        "input-model.js"
                        "modal-dialog/modal-dialog-model.js"
                        "register-components.js"]
                   :html ["fill-info-button/fill-info-button-template.html"
                          "foreman-history/foreman-history-template.html"
                          "foreman-other-applications/foreman-other-applications-template.html"
                          "string/string-template.html"
                          "select/select-template.html"
                          "checkbox/checkbox-template.html"
                          "modal-dialog/modal-dialog-template.html"]}

   :application  {:depends [:common-html :global-models :repository :tree :create-task :modal-datepicker :signing :invites :side-panel :verdict-attachment-prints :ui-components]
                  :js ["add-link-permit.js" "map-model.js" "change-location.js" "invite.js" "verdicts-model.js"
                       "add-operation.js" "foreman-model.js"
                       "request-statement-model.js" "add-party.js" "attachments-tab-model.js"
                       "invite-company.js" "application.js"]
                  :html ["attachment-actions-template.html" "attachments-template.html" "add-link-permit.html" "application.html" "inforequest.html" "add-operation.html"
                         "change-location.html" "invite-company.html" "foreman-template.html"]}

   :applications {:depends [:common-html :repository :invites]
                  :html ["applications-list.html"]
                  :js ["applications-list.js"]}

   :statement    {:depends [:common-html :repository :side-panel]
                  :js ["statement.js"]
                  :html ["statement.html"]}

   :verdict      {:depends [:common-html :repository :attachment]
                  :js ["verdict.js"]
                  :html ["verdict.html"]}

   :neighbors    {:depends [:common-html :repository :side-panel]
                  :js ["neighbors.js"]
                  :html ["neighbors.html"]}

   :register     {:depends [:common-html]
                  :js ["registration-models.js" "register.js"
                       "company-registration.js"]
                  :html ["register.html" "register2.html" "register3.html"
                         "register-company.html" "register-company-success.html" "register-company-fail.html"]}

   :link-account {:depends [:register]
                  :js ["link-account.js"]
                  :html ["link-account-1.html" "link-account-2.html" "link-account-3.html"]}

   :docgen       {:depends [:accordion :common-html]
                  :js ["docmodel.js" "docgen.js"]}

   :create       {:depends [:common-html]
                  :js ["municipalities.js" "create.js"]
                  :html ["create.html"]
                  :css ["create.css"]}

   :iframe       {:depends [:common-html]
                  :css ["iframe.css"]}

   :login        {:depends [:common-html]
                  :js      ["login.js"]}

   :users        {:js ["users.js"]
                  :html ["users.html"]}

   :company      {:js ["company.js"]
                  :html ["company.html"]
                  :css ["company.css"]}

   :admins       {:depends [:users]}

   :notice       {:js ["notice.js"]
                  :html ["notice.html"]}

   :side-panel   {:js ["side-panel.js"]
                  :html ["side-panel.html"]}

   :password-reset {:depends [:common-html]
                    :js ["password-reset.js"]
                    :html ["password-reset.html"]}

   :integration-error {:js [ "integration-error.js"]
                       :html ["integration-error.html"]}

   ;; Single Page Apps and standalone components:
   ;; (compare to auth-methods in web.clj)

   :hashbang     {:depends [:common-html]
                  :html ["index.html"]}

   :upload       {:depends [:iframe]
                  :js ["upload.js"]
                  :css ["upload.css"]}

   :applicant-app {:js ["applicant.js"]}
   :applicant     {:depends [:applicant-app
                             :common-html :authenticated :map :applications :application
                             :task :statement :docgen :create :mypage :user-menu :debug
                             :company]}

   :authority-app {:js ["authority.js"]}
   :authority     {:depends [:authority-app :common-html :authenticated :map :applications :notice :application
                             :task :statement :verdict :neighbors :docgen :create :mypage :user-menu :debug
                             :company :stamp :integration-error]}

   :oir-app {:js ["oir.js"]}
   :oir     {:depends [:oir-app :common-html :authenticated :map :application :attachment
                       :docgen :debug :notice]
             :css ["oir.css"]}

   :authority-admin-app {:js ["authority-admin.js"]}
   :authority-admin     {:depends [:authority-admin-app :common-html :authenticated :admins :mypage :user-menu :debug]
                         :js ["admin.js" schema-versions-by-permit-type]
                         :html ["admin.html"]}

   :admin-app {:js ["admin.js"]}
   :admin     {:depends [:admin-app :common-html :authenticated :admins :map :mypage :user-menu :debug]
               :css ["admin.css"]
               :js ["admin-users.js" "organizations.js" "companies.js" "features.js" "actions.js" "screenmessages-list.js"]
               :html ["index.html" "admin.html"
                      "admin-users.html" "organizations.html" "companies.html" "features.html" "actions.html"
                      "screenmessages-list.html"]}

   :wordpress {:depends [:login :password-reset]}

   :welcome-app {:js ["welcome.js"]}
   :welcome {:depends [:welcome-app :login :register :link-account :debug :user-menu :screenmessages :password-reset]
             :js ["company-user.js"]
             :html ["index.html" "login.html" "company-user.html"]}

   :oskari  {:css ["oskari.css"]}

   :neighbor-app {:js ["neighbor-app.js"]}
   :neighbor {:depends [:neighbor-app :common-html :map :debug :docgen :debug :user-menu :screenmessages]
              :html ["neighbor-show.html"]
              :js ["neighbor-show.js"]}})

; Make sure all dependencies are resolvable:
(doseq [[component {dependencies :depends}] ui-components
        dependency dependencies]
  (if-not (contains? ui-components dependency)
    (throw (Exception. (format "Component '%s' has dependency to missing component '%s'" component dependency)))))

; Make sure that all resources are available:
(doseq [c (keys ui-components)
        r (mapcat #(c/component-resources ui-components % c) [:js :html :css])]
  (if (not (fn? r))
    (let [resource (.getResourceAsStream (clojure.lang.RT/baseLoader) (c/path r))]
      (if resource
        (.close resource)
        (throw (Exception. (str "Resource missing: " r)))))))
