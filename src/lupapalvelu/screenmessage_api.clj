(ns lupapalvelu.screenmessage-api
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info warn error errorf fatal]]
            [lupapalvelu.action :refer [defcommand defquery]]
            [lupapalvelu.mongo :as mongo]
            [sade.core :refer [ok]]))


(defquery screenmessages
  {:roles [:anonymous]}
  [_]
  (ok :screenmessages (mongo/select :screenmessages)))

(defcommand screenmessages-add
  {:parameters [fi sv]
   :roles      [:admin]}
  [{created :created}]
  (mongo/insert :screenmessages {:id (mongo/create-id)
                                 :added created
                                 :fi fi
                                 :sv (if-not (empty? sv) sv fi)}))

(defcommand screenmessages-reset
  {:roles      [:admin]}
  [_]
  (mongo/drop-collection :screenmessages))
