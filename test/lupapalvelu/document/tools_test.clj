(ns lupapalvelu.document.tools-test
  (:use [lupapalvelu.document.tools]
        [midje.sweet]
        [midje.util :only [expose-testables]]))

(expose-testables lupapalvelu.document.tools)

(def schema
  {:info {:name "band"},
   :body
   [{:name "band",
     :type :group,
     :body
     [{:name "name", :type :string}
      {:name "genre", :type :string}
      {:name "members"
       :type :group
       :repeating true
       :body [{:name "name", :type :string}
              {:name "instrument", :type :string}]}]}]})

(def expected-simple-document
  {:band {:name nil
          :genre nil
          :members {:0 {:name nil
                        :instrument nil}}}})

(def expected-wrapped-simple-document
  {:band {:name {:value nil}
          :genre {:value nil}
          :members {:0 {:name {:value nil}
                        :instrument {:value nil}}}}})

(fact "simple schema"
  (-> schema
    (create nil-values)
    flattened) => expected-simple-document)

(fact "simple schema with wrapped values"
  (-> schema
    (create nil-values)
    flattened
    (wrapped :value)) => expected-wrapped-simple-document)

;;
;; Public api
;;

(fact "wrapped defaults to :value key"
  (wrapped nil) => {:value nil}
  (wrapped {:value nil}) => {:value {:value nil}})

(fact "unwrapped"
  (unwrapped {:k {:value nil}}) => {:k nil}
  (unwrapped expected-wrapped-simple-document :value) => expected-simple-document
  (unwrapped (wrapped expected-simple-document)) => expected-simple-document)

(fact "create-dummy-document-data"
  (create-document-data schema) => expected-wrapped-simple-document)

(def expected-wrapped-simple-document-timestamped
  {:band {:name {:value nil :modified nil}
          :genre {:value nil :modified nil}
          :members {:0 {:name {:value nil :modified nil}
                        :instrument {:value nil :modified nil}}}}})

(fact "timestampeds"
  (timestamped nil nil) => nil
  (timestamped {} nil) => {}
  (timestamped expected-wrapped-simple-document nil) => expected-wrapped-simple-document-timestamped)

(fact "schema-body-without-element-by-name"
  (schema-body-without-element-by-name (:body schema) "band") => []
  (schema-body-without-element-by-name (:body schema) "invalid") => (:body schema)
  (schema-body-without-element-by-name (:body schema) "members") => [{:name "band"
                                                                      :type :group
                                                                      :body [{:name "name"
                                                                              :type :string}
                                                                             {:name "genre"
                                                                              :type :string}]}])

(fact "strip-elements-by-name"
  (schema-without-element-by-name schema "band") => {:info {:name "band"} :body []}
  (schema-without-element-by-name schema "INVALID") => schema
  (schema-without-element-by-name schema "band") => {:info {:name "band"} :body []})
