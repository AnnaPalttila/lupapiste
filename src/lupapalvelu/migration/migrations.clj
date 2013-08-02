(ns lupapalvelu.migration.migrations
  (:require [lupapalvelu.migration.core :refer [defmigration]]
            [lupapalvelu.mongo :as mongo]
            [monger.operators :refer :all]))

(defmigration add-default-permit-type {:pre (= (->> :applications mongo/select (keep :permitType) count) 0)
                                       :post (= (->> :applications mongo/select (keep :permitType) count) (mongo/count :applications))}
  (mongo/update :applications {:permitType {$exists false}} {$set {:permitType "R"}} :multi true))  


(defmigration add-scope-to-organizations
  (let [organizations                        (mongo/select :organizations)
        without-scope                        (filter (comp not :scope) organizations)
        organizations-without-scope-before   (count without-scope)]
    (doseq [{:keys [id municipalities]} without-scope]
      (let [scopes (map (fn [municipality] {:municipality municipality :permitType "R"}) municipalities)]
        (mongo/update-by-id :organizations id {$set {:scope scopes}})))
    {:organizations-total                 (count organizations)
     :organizations-without-scope-before  organizations-without-scope-before
     :organizations-without-scope-after   (mongo/count :organizations {:scope {$exists false}})}))

