(ns lupapalvelu.organization-api
  (:import [org.geotools.data FileDataStoreFinder DataUtilities]
           [org.geotools.geojson.feature FeatureJSON]
           [org.geotools.feature.simple SimpleFeatureBuilder]
           [org.geotools.referencing.crs DefaultGeographicCRS]
           [java.util ArrayList])

  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info warn error errorf fatal]]
            [clojure.set :as set]
            [clojure.string :as s]
            [clojure.walk :refer [keywordize-keys]]
            [cheshire.core :as cheshire]
            [monger.operators :refer :all]
            [noir.core :refer [defpage]]
            [noir.response :as resp]
            [noir.request :as request]
            [camel-snake-kebab :as csk]
            [me.raynes.fs :as fs]
            [slingshot.slingshot :refer [try+]]
            [sade.core :refer [ok fail fail! now]]
            [sade.util :as util]
            [sade.env :as env]
            [sade.strings :as ss]
            [sade.property :as p]
            [lupapalvelu.action :refer [defquery defcommand defraw non-blank-parameters vector-parameters boolean-parameters number-parameters email-validator] :as action]
            [lupapalvelu.states :as states]
            [lupapalvelu.wfs :as wfs]
            [lupapalvelu.mime :as mime]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.user :as user]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.organization :as o]
            [lupapalvelu.logging :as logging]
            [lupapalvelu.geojson :as geo]))
;;
;; local api
;;

(defn- municipalities-with-organization []
  (let [organizations (o/get-organizations {} [:scope :krysp])]
    {:all (distinct
            (for [{scopes :scope} organizations
                  {municipality :municipality} scopes]
              municipality))
     :with-backend (remove nil?
                     (distinct
                       (for [{scopes :scope :as org} organizations
                             {municipality :municipality :as scope} scopes]
                         (when (-> org :krysp (get (-> scope :permitType keyword)) :url s/blank? not)
                           municipality))))}))

(defn- organization-attachments
  "Returns a map where key is permit type, value is a list of attachment types for the permit type"
  [{scope :scope}]
  (reduce #(assoc %1 %2 (attachment/get-attachment-types-by-permit-type %2)) {} (map (comp keyword :permitType) scope)))

(defn- organization-operations-with-attachments
  "Returns a map where key is permit type, value is a list of operations for the permit type"
  [{scope :scope :as organization}]
  (let [selected-ops (->> organization :selected-operations (map keyword) set)]
    (reduce
      (fn [result-map permit-type]
        (if-not (result-map permit-type)
          (let [operation-names (keys (filter (fn [[_ op]] (= permit-type (:permit-type op))) operations/operations))
                empty-operation-attachments (zipmap operation-names (repeat []))
                saved-operation-attachments (select-keys (:operations-attachments organization) operation-names)
                all-operation-attachments (merge empty-operation-attachments saved-operation-attachments)

                selected-operation-attachments (into {} (filter (fn [[op attachments]] (selected-ops op)) all-operation-attachments))]
            (assoc result-map permit-type selected-operation-attachments))
          result-map))
     {}
     (map :permitType scope))))

(defn- selected-operations-with-permit-types
  "Returns a map where key is permit type, value is a list of operations for the permit type"
  [{scope :scope selected-ops :selected-operations}]
  (reduce
    #(if-not (get-in %1 [%2])
       (let [selected-operations (set (map keyword selected-ops))
             operation-names (keys (filter
                                     (fn [[name op]]
                                       (and
                                         (= %2 (:permit-type op))
                                         (selected-operations name)))
                                     operations/operations))]
         (if operation-names (assoc %1 %2 operation-names) %1))
       %1)
    {}
    (map :permitType scope)))

;;
;; Actions
;;

(defquery organization-by-user
  {:description "Lists organization details."
   :user-roles #{:authorityAdmin}}
  [{user :user}]
  (let [organization (o/get-organization (user/authority-admins-organization-id user))
        ops-with-attachments (organization-operations-with-attachments organization)
        selected-operations-with-permit-type (selected-operations-with-permit-types organization)
        allowed-roles (o/allowed-roles-in-organization organization)]
    (ok :organization (-> organization
                        (assoc :operationsAttachments ops-with-attachments
                               :selectedOperations selected-operations-with-permit-type
                               :allowedRoles allowed-roles)
                        (dissoc :operations-attachments :selected-operations))
        :attachmentTypes (organization-attachments organization))))

(defquery user-organizations-for-permit-type
  {:parameters [permitType]
   :user-roles #{:authority}
   :input-validators [permit/permit-type-validator]}
  [{user :user}]
  (ok :organizations (o/get-organizations {:_id {$in (user/organization-ids-by-roles user #{:authority})}
                                           :scope {$elemMatch {:permitType permitType}}})))

(defcommand update-organization
  {:description "Update organization details."
   :parameters [permitType municipality
                inforequestEnabled applicationEnabled openInforequestEnabled openInforequestEmail
                opening]
   :input-validators [permit/permit-type-validator]
   :user-roles #{:admin}}
  [_]
  (mongo/update-by-query :organizations
      {:scope {$elemMatch {:permitType permitType :municipality municipality}}}
      {$set {:scope.$.inforequest-enabled inforequestEnabled
             :scope.$.new-application-enabled applicationEnabled
             :scope.$.open-inforequest openInforequestEnabled
             :scope.$.open-inforequest-email openInforequestEmail
             :scope.$.opening (when (number? opening) opening)}})
  (ok))

(defcommand add-organization-link
  {:description "Adds link to organization."
   :parameters [url nameFi nameSv]
   :user-roles #{:authorityAdmin}
   :input-validators [(partial non-blank-parameters [:url :nameFi :nameSv])]}
  [{user :user}]
  (o/update-organization (user/authority-admins-organization-id user) {$push {:links {:name {:fi nameFi :sv nameSv} :url url}}})
  (ok))

(defcommand update-organization-link
  {:description "Updates organization link."
   :parameters [url nameFi nameSv index]
   :user-roles #{:authorityAdmin}
   :input-validators [(partial non-blank-parameters [:url :nameFi :nameSv :index])
                      (partial number-parameters [:index])]}
  [{user :user}]
  (o/update-organization (user/authority-admins-organization-id user) {$set {(str "links." index) {:name {:fi nameFi :sv nameSv} :url url}}})
  (ok))

(defcommand remove-organization-link
  {:description "Removes organization link."
   :parameters [url nameFi nameSv]
   :user-roles #{:authorityAdmin}}
  [{user :user}]
  (o/update-organization (user/authority-admins-organization-id user) {$pull {:links {:name {:fi nameFi :sv nameSv} :url url}}})
  (ok))

(defquery organizations
  {:user-roles #{:admin}}
  [_]
  (ok :organizations (o/get-organizations)))

(defquery organization-by-id
  {:parameters [organizationId]
   :user-roles #{:admin}}
  [_]
  (o/get-organization organizationId))

(defquery municipalities-with-organization
  {:description "Returns a list of municipality IDs that are affiliated with Lupapiste."
   :user-roles #{:applicant :authority}}
  [_]
  (let [munis (municipalities-with-organization)]
    (ok
      :municipalities (:all munis)
      :municipalitiesWithBackendInUse (:with-backend munis))))

(defquery municipality-active
  {:parameters [municipality]
   :user-roles #{:anonymous}}
  [_]
  (let [organizations (o/get-organizations {:scope.municipality municipality})
        scopes (->> organizations
                 (map :scope)
                 flatten
                 (filter #(= municipality (:municipality %))))]
      (ok
        :applications (->> scopes (filter :new-application-enabled) (map :permitType))
        :infoRequests (->> scopes (filter :inforequest-enabled) (map :permitType))
        :opening (->> scopes (filter :opening) (map #(select-keys % [:permitType :opening]))))))

(defquery municipality-by-property-id
  {:parameters [propertyId]
   :user-roles #{:anonymous}}
  [_]
  (if-let [municipality (p/municipality-id-by-property-id propertyId)]
    (ok :municipality municipality)
    (fail :municipalitysearch.notfound)))

(defquery all-operations-for-organization
  {:description "Returns operations that match the permit types of the organization whose id is given as parameter"
   :parameters [organizationId]
   :user-roles #{:authorityAdmin}
   :input-validators [(partial non-blank-parameters [:organizationId])]}
  (when-let [org (o/get-organization organizationId)]
    (ok :operations (operations/organization-operations org))))

(defquery selected-operations-for-municipality
  {:description "Returns selected operations of all the organizations who have a scope with the given municipality.
                 If a \"permitType\" parameter is given, returns selected operations for only that organization (the municipality + permitType combination)."
   :parameters [:municipality]
   :user-roles #{:applicant :authority :authorityAdmin}
   :input-validators [(partial non-blank-parameters [:municipality])]}
  [{{:keys [municipality permitType]} :data}]
  (when-let [organizations (o/resolve-organizations municipality permitType)]
    (ok :operations (operations/selected-operations-for-organizations organizations))))

(defquery addable-operations
  {:description "returns operations addable for the application whose id is given as parameter"
   :parameters  [:id]
   :user-roles #{:applicant :authority}
   :states      states/pre-sent-application-states}
  [{{:keys [organization permitType]} :application}]
  (when-let [org (o/get-organization organization)]
    (let [selected-operations (map keyword (:selected-operations org))]
      (ok :operations (operations/addable-operations selected-operations permitType)))))

(defquery organization-details
  {:description "Resolves organization based on municipality and selected operation."
   :parameters [municipality operation]
   :user-roles #{:applicant :authority}}
  [_]
  (let [permit-type (:permit-type ((keyword operation) operations/operations))]
    (if-let [organization (o/resolve-organization municipality permit-type)]
      (let [scope (o/resolve-organization-scope municipality permit-type organization)]
        (ok
          :inforequests-disabled (not (:inforequest-enabled scope))
          :new-applications-disabled (not (:new-application-enabled scope))
          :links (:links organization)
          :attachmentsForOp (-> organization :operations-attachments ((keyword operation)))))
      (fail :municipalityNotSupported :municipality municipality :permitType permit-type))))

(defcommand set-organization-selected-operations
  {:parameters [operations]
   :user-roles #{:authorityAdmin}
   :input-validators  [(partial non-blank-parameters [:operations])
                       (partial vector-parameters [:operations])
                       (fn [{{:keys [operations]} :data}]
                         (when-not (every? (->> operations/operations keys (map name) set) operations)
                           (fail :error.unknown-operation)))]}
  [{user :user}]
  (o/update-organization (user/authority-admins-organization-id user) {$set {:selected-operations operations}})
  (ok))

(defcommand organization-operations-attachments
  {:parameters [operation attachments]
   :user-roles #{:authorityAdmin}
   :input-validators [(partial non-blank-parameters [:operation])
                      (partial vector-parameters [:attachments])
                      (fn [{{:keys [operation attachments]} :data, user :user}]
                        (let [organization (o/get-organization (user/authority-admins-organization-id user))
                              selected-operations (set (:selected-operations organization))
                              permit-type (get-in operations/operations [(keyword operation) :permit-type] )
                              allowed-types (when permit-type (attachment/get-attachment-types-by-permit-type permit-type))
                              attachment-types (map (fn [[group id]] {:type-group group :type-id id}) attachments)]
                          (cond
                            (not (selected-operations operation)) (do
                                                                    (error "Unknown operation: " (logging/sanitize 100 operation))
                                                                    (fail :error.unknown-operation))
                            (not (every? (partial attachment/allowed-attachment-types-contain? allowed-types) attachment-types)) (fail :error.unknown-attachment-type))))]}
  [{user :user}]
  (o/update-organization (user/authority-admins-organization-id user) {$set {(str "operations-attachments." operation) attachments}})
  (ok))

(defcommand set-organization-app-required-fields-filling-obligatory
  {:parameters [enabled]
   :user-roles #{:authorityAdmin}
   :input-validators  [(partial boolean-parameters [:enabled])]}
  [{user :user}]
  (o/update-organization (user/authority-admins-organization-id user) {$set {:app-required-fields-filling-obligatory enabled}})
  (ok))

(defcommand set-organization-validate-verdict-given-date
  {:parameters [enabled]
   :user-roles #{:authorityAdmin}
   :input-validators  [(partial boolean-parameters [:enabled])]}
  [{user :user}]
  (o/update-organization (user/authority-admins-organization-id user) {$set {:validate-verdict-given-date enabled}})
  (ok))

(defcommand set-organization-permanent-archive-enabled
  {:parameters [enabled organizationId]
   :user-roles #{:admin}
   :input-validators  [(partial non-blank-parameters [:enabled :organizationId])
                       (partial boolean-parameters [:enabled])]}
  [{user :user}]
  (o/update-organization organizationId {$set {:permanent-archive-enabled enabled}})
  (ok))

(defquery krysp-config
  {:user-roles #{:authorityAdmin}}
  [{user :user}]
  (let [organization-id (user/authority-admins-organization-id user)]
    (if-let [organization (o/get-organization organization-id)]
      (let [permit-types (mapv (comp keyword :permitType) (:scope organization))
            krysp-keys (if (env/feature? :kunnan-osoiteaineisto) (conj permit-types :osoitteet) permit-types)
            empty-confs (zipmap krysp-keys (repeat {}))]
        (ok :krysp (merge empty-confs (:krysp organization))))
      (fail :error.unknown-organization))))

(defcommand set-krysp-endpoint
  {:parameters [url username password permitType version]
   :user-roles #{:authorityAdmin}
   :input-validators [(fn [{{permit-type :permitType} :data}]
                        (when-not (or
                                    (and (env/feature? :kunnan-osoiteaineisto) (= "osoitteet" permit-type))
                                    (permit/valid-permit-type? permit-type))
                          (fail :error.missing-parameters :parameters [:permitType])))]}
  [{user :user}]
  (let [organization-id (user/authority-admins-organization-id user)
        krysp-config    (o/get-krysp-wfs {:_id organization-id} permitType)
        password        (if (s/blank? password) (second (:credentials krysp-config)) password)]
    (if (or (s/blank? url) (wfs/wfs-is-alive? url username password))
      (o/set-krysp-endpoint organization-id url username password permitType version)
      (fail :auth-admin.legacyNotResponding))))

(defcommand set-kopiolaitos-info
  {:parameters [kopiolaitosEmail kopiolaitosOrdererAddress kopiolaitosOrdererPhone kopiolaitosOrdererEmail]
   :user-roles #{:authorityAdmin}
   :input-validators [(fn [{{email-str :kopiolaitosEmail} :data :as command}]
                        (let [emails (util/separate-emails email-str)]
                          ;; action/email-validator returns nil if email was valid
                          (when (some #(email-validator :email {:data {:email %}}) emails)
                            (fail :error.set-kopiolaitos-info.invalid-email))))]}
  [{user :user}]
  (o/update-organization (user/authority-admins-organization-id user)
    {$set {:kopiolaitos-email kopiolaitosEmail
           :kopiolaitos-orderer-address kopiolaitosOrdererAddress
           :kopiolaitos-orderer-phone kopiolaitosOrdererPhone
           :kopiolaitos-orderer-email kopiolaitosOrdererEmail}})
  (ok))

(defquery kopiolaitos-config
  {:user-roles #{:authorityAdmin}}
  [{user :user}]
  (let [organization-id (user/authority-admins-organization-id user)]
    (if-let [organization (o/get-organization organization-id)]
      (ok
        :kopiolaitos-email (:kopiolaitos-email organization)
        :kopiolaitos-orderer-address (:kopiolaitos-orderer-address organization)
        :kopiolaitos-orderer-phone (:kopiolaitos-orderer-phone organization)
        :kopiolaitos-orderer-email (:kopiolaitos-orderer-email organization))
      (fail :error.unknown-organization))))

(defquery get-organization-names
  {:description "Returns an organization id -> name map. (Used by TOJ.)"
   :user-roles #{:anonymous}}
  [_]
  (ok :names (into {} (for [{:keys [id name]} (o/get-organizations {})]
                        [id name]))))

(defquery vendor-backend-redirect-config
  {:user-roles #{:authorityAdmin}}
  [{user :user}]
  (let [organization-id (user/authority-admins-organization-id user)]
    (if-let [organization (o/get-organization organization-id)]
      (ok (:vendor-backend-redirect organization))
      (fail :error.unknown-organization))))

(defcommand save-vendor-backend-redirect-config
  {:parameters       [key val]
   :user-roles       #{:authorityAdmin}
   :input-validators [(fn [{{key :key} :data}]
                        (when-not (contains? #{:vendorBackendUrlForBackendId :vendorBackendUrlForLpId} (keyword key))
                          (fail :error.illegal-key)))
                      (fn [{{url :val} :data}]
                        (when-not (ss/blank? url)
                          (util/validate-url url)))]}
  [{user :user}]
  (let [key    (csk/->kebab-case key)
        org-id (user/authority-admins-organization-id user)]
    (o/update-organization org-id {$set {(str "vendor-backend-redirect." key) val}})))

(defcommand save-organization-tags
  {:parameters [tags]
   :user-roles #{:authorityAdmin}}
  [{user :user}]
  (let [org-id (user/authority-admins-organization-id user)
        old-tag-ids (set (map :id (:tags (o/get-organization org-id))))
        new-tag-ids (set (map :id tags))
        removed-ids (set/difference old-tag-ids new-tag-ids)]
    (when (seq removed-ids)
      (mongo/update-by-query :applications {:tags {$in removed-ids} :organization org-id} {$pull {:tags {$in removed-ids}}}))
    (o/update-organization org-id {$set {:tags (o/create-tag-ids tags)}})))

(defquery remove-tag-ok
  {:parameters [tagId]
   :user-roles #{:authorityAdmin}}
  [{user :user}]
  (let [org-id (user/authority-admins-organization-id user)]
    (when-let [tag-applications (seq (mongo/select
                                       :applications
                                       {:tags tagId :organization org-id}
                                       [:_id]))]
      (fail :warning.tags.removing-from-applications :applications tag-applications))))

(defquery get-organization-tags
  {:user-authz-roles #{:statementGiver}
   :org-authz-roles action/reader-org-authz-roles
   :user-roles #{:authorityAdmin :authority}}
  [{{:keys [orgAuthz] :as user} :user}]
  (if (seq orgAuthz)
    (let [organization-tags (mongo/select
                                  :organizations
                                  {:_id {$in (keys orgAuthz)} :tags {$exists true}}
                                  [:tags :name])
          result (map (juxt :id #(select-keys % [:tags :name])) organization-tags)]
      (ok :tags (into {} result)))
    (ok :tags {})))

(defquery get-organization-areas
  {:user-authz-roles #{:statementGiver}
   :org-authz-roles  action/reader-org-authz-roles
   :user-roles       #{:authorityAdmin :authority}}
  [{{:keys [orgAuthz] :as user} :user}]
  (if (seq orgAuthz)
    (let [organization-areas (mongo/select
                               :organizations
                               {:_id {$in (keys orgAuthz)} :areas {$exists true}}
                               [:areas :name])
          result (map (juxt :id #(select-keys % [:areas :name])) organization-areas)]
      (ok :areas (into {} result)))
    (ok :areas {})))

(defn-
  ^org.geotools.data.simple.SimpleFeatureCollection
  transform-crs-to-wgs84
  "Convert feature crs in collection to WGS84"
  [^org.geotools.feature.FeatureCollection collection]
  (let [iterator (.features collection)
        list (ArrayList.)
        _ (loop [feature (when (.hasNext iterator)
                           (.next iterator))]
            (when feature
              ; Set CRS to WGS84 to bypass problems when converting to GeoJSON (CRS detection is skipped with WGS84).
              ; Atm we assume only CRS EPSG:3067 is used.
              (let [feature-type (DataUtilities/createSubType (.getFeatureType feature) nil DefaultGeographicCRS/WGS84)
                    builder (SimpleFeatureBuilder. feature-type) ; build new feature with changed crs
                    _ (.init builder feature) ; init builder with original feature
                    transformed-feature (.buildFeature builder (mongo/create-id))]
                (.add list transformed-feature)))
            (when (.hasNext iterator)
              (recur (.next iterator))))]
    (.close iterator)
    (DataUtilities/collection list)))


(defraw organization-area
  {:user-roles #{:authorityAdmin}}
  [{user :user {[{:keys [tempfile filename size]}] :files created :created} :data :as action}]
  (let [org-id (user/authority-admins-organization-id user)
        filename (mime/sanitize-filename filename)
        content-type (mime/mime-type filename)
        file-info {:file-name    filename
                   :content-type content-type
                   :size         size
                   :organization org-id
                   :created      created}]

    (try+
      (when-not (= content-type "application/zip")
        (fail! :error.illegal-shapefile))

      (let [target-dir (util/unzip (.getPath tempfile) (fs/temp-dir "area"))
            shape-file (first (util/get-files-by-regex (.getPath target-dir) #"^.+\.shp$"))
            data-store (FileDataStoreFinder/getDataStore shape-file)
            new-collection (some-> data-store
                             .getFeatureSource
                             .getFeatures
                             transform-crs-to-wgs84)
            areas (keywordize-keys (cheshire/parse-string (.toString (FeatureJSON.) new-collection)))
            ensured-areas (geo/ensure-features areas)]
        (when (geo/validate-features (:features ensured-areas))
          (fail! :error.coordinates-not-epsg3067))
        (o/update-organization org-id {$set {:areas ensured-areas}})
        (.dispose data-store)
        (->> (assoc file-info :areas ensured-areas :ok true)
          (resp/json)
          (resp/content-type "application/json")
          (resp/status 200)))
      (catch [:sade.core/type :sade.core/fail] {:keys [text] :as all}
        (resp/status 400 text))
      (catch Throwable t
        (error "Failed to parse shapefile" t)
        (resp/status 400 :error.shapefile-parsing-failed)))))

