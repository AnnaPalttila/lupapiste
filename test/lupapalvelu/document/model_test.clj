(ns lupapalvelu.document.model-test
  (:use [lupapalvelu.document.model]
        [lupapalvelu.document.validators]
        [midje.sweet])
  (:require [lupapalvelu.document.schemas :as schemas]))

;; Define a "random" timestamp used in test.
;; Midje metaconstraints seems to mess with tools/unwrapped.
(def some-time 123456789)

;; Simple test schema:

(def schema {:info {:name "test-model"
                    :version 1}
             :body [{:name "a" :type :group
                     :body [{:name "aa" :type :string}
                            {:name "ab" :type :string :min-len 2 :max-len 3}
                            {:name "ac" :type :string :min-len 2 :max-len 3}
                            {:name "b" :type :group
                             :body [{:name "ba" :type :string :min-len 2}
                                    {:name "bb" :type :boolean}]}
                            {:name "c" :type :list
                             :body [{:name "ca" :type :string}
                                    {:name "cb" :type :checkbox}]}
                            {:name "d" :type :select
                             :body [{:name "A"}
                                    {:name "B"}
                                    {:name "C"}]}]}]})

(def schema-with-repetition {:info {:name "repetition-model" :version 1}
                             :body [{:name "single" :type :string}
                                    {:name "repeats" :type :group :repeating true
                                     :body [{:name "single2" :type :string}
                                            {:name "repeats2" :type :string :subtype :digit :repeating true}]}]})

(facts "Find-by-name"
  (fact (find-by-name (:body schema) ["a"])          => (-> schema :body first))
  (fact (find-by-name (:body schema) ["a" "aa"])     => {:name "aa" :type :string})
  (fact (find-by-name (:body schema) ["a" "b" "bb"]) => {:name "bb" :type :boolean})
  (fact (find-by-name (:body schema) [:a :b :bb])    => {:name "bb" :type :boolean})
  (fact (find-by-name (:body schema) ["a" "b" "bc"]) => nil))

;; Tests for internals:

(facts "has-errors?"
  (has-errors? [])                  => false
  (has-errors? [{:result [:warn]}]) => false
  (has-errors? [{:result [:warn]}
                {:result [:err]}])  => true)

; field type validation

(facts "dates"
  (validate-field {:type :date} "abba") => [:warn "illegal-value:date"]
  (validate-field {:type :date} "") => nil
  (validate-field {:type :date} "11.12.2013") => nil)

;;
;; validate
;;

(facts "validate"
  {:schema {:info {:name "schema"}
            :body [{:name "a" :type :group
                    :body [{:name "aa" :type :string}
                           {:name "ab" :type :string :min-len 2 :max-len 3}]}]}
   :data {:a {:aa {:value "kukka"}
              :ab {:value "123"}}}} => valid?

  {:schema {:info {:name "schema"}
            :body [{:name "a" :type :group
                    :body [{:name "aa" :type :string}
                           {:name "ab" :type :string :min-len 2 :max-len 3}]}]}
   :data {:c {:aa {:value "kukka"}
              :ab {:value "123"}}}} => invalid?)

;; Validation tests:

(facts "Simple validations"
  (with-timestamp some-time
    (let [document (new-document schema ..now..)]
      (-> document
        (apply-update [:a :ab] "foo"))   => valid?
      (-> document
        (apply-update [:a :ab] "f"))     => (invalid-with? [:warn "illegal-value:too-short"])
      (-> document
        (apply-update [:a :ab] "foooo")) => (invalid-with? [:err "illegal-value:too-long"])
      (-> document
        (apply-update [:a :ab] "\u00d6\u00e9\u00c8")) => valid?
      (-> document
        (apply-update [:a :ab] "\u047e\u0471")) => (invalid-with? [:warn "illegal-value:not-latin1-string"]))))

(facts "Select"
  (with-timestamp some-time
    (let [document (new-document schema ..now..)]
      (-> document
        (apply-update [:a :d] "A")) => valid?
      (-> document
        (apply-update [:a :d] "")) => valid?
      (-> document
        (apply-update [:a :d] "D")) => (invalid-with? [:warn "illegal-value:select"]))))

(facts "with real schemas - important field for paasuunnittelija"
  (with-timestamp some-time
    (let [document (new-document (schemas/get-schema "paasuunnittelija") ..now..)]
      (-> document
        (apply-update [:henkilotiedot :etunimi] "Tauno")
        (apply-update [:henkilotiedot :sukunimi] "Palo")
        (apply-update [:yritys :liikeJaYhteisoTunnus] "1060155-5")
        (apply-update [:yritys :yritysnimi] "Suunnittelu Palo")
        (apply-update [:osoite :katu] "katu")
        (apply-update [:osoite :postinumero] "12345")
        (apply-update [:osoite :postitoimipaikannimi] "Demola")
        (apply-update [:patevyys :koulutus] "Demotehti")
        (apply-update [:patevyys :patevyysluokka] "AA")
        (apply-update [:yhteystiedot :email] "tauno@example.com")
        (apply-update [:yhteystiedot :puhelin] "050")) => valid?
      (-> document
        (apply-update [:henkilotiedot :etunimiz] "Tauno")) => (invalid-with? [:err "illegal-key"])
      (-> document
        (apply-update [:henkilotiedot :sukunimiz] "Palo")) => (invalid-with? [:err "illegal-key"]))))

(facts "Repeating section"
  (with-timestamp some-time
    (let [document (new-document schema-with-repetition ..now..)]
      (fact "Single value contains no nested sections"
        (-> document
          (apply-update [:single :1 :single2] "foo")) => (invalid-with? [:err "illegal-key"]))

      (fact "Repeating section happy case"
        (-> document
          (apply-update [:repeats :1 :single2] "foo")) => valid?)

      (fact "Invalid key under nested section"
        (-> document
          (apply-update [:repeats :1 :single3] "foo")) => (invalid-with? [:err "illegal-key"]))

      (fact "Unindexed repeating section"
        (-> document
          (apply-update [:repeats :single2] "foo")) => (invalid-with? [:err "illegal-key"]))

      (fact "Repeating string, 0"
        (-> document
          (apply-update [:repeats :1 :repeats2 :0] "1")) => valid?)

      (fact "Repeating string, 1"
        (-> document
          (apply-update [:repeats :1 :repeats2 :1] "foo")) => (invalid-with? [:warn "illegal-number"])))))

(def schema-with-required {:info {:name "with-required" :version 1}
                           :body [{:name "a" :type :group
                                   :body [{:name "b" :type :group
                                           :body [{:name "aa" :type :string :required true}
                                                  {:name "ab" :type :string :required true}]}
                                          {:name "c" :type :group :repeating true
                                           :body [{:name "raa" :type :string}
                                                  {:name "rab" :type :string :required true}]}
                                          {:name "d" :type :group :repeating true
                                           :body [{:name "d2" :type :group :repeating true
                                                   :body [{:name "od1" :type :string}
                                                          {:name "rd" :type :string :required true}
                                                          {:name "od2" :type :string}]}]}]}]})

(def missing-required-fields? (invalid-with? [:tip "illegal-value:required"]))

(facts "Required fields"
  (with-timestamp some-time
    (let [document (new-document schema-with-required ..now..)]

      document => missing-required-fields?

      (-> document
        (apply-update [:a :b :aa] " ")
        (apply-update [:a :b :ab] " ")) => missing-required-fields?

      (-> document
        (apply-update [:a :b :aa] "value")
        (apply-update [:a :b :ab] "value")) => valid?

      (-> document
        (apply-update [:a :b :aa] "value")
        (apply-update [:a :b :ab] "value")
        (apply-update [:a :c :0 :raa] "value")) => missing-required-fields?

      (-> document
        (apply-update [:a :b :aa] "value")
        (apply-update [:a :b :ab] "value")
        (apply-update [:a :c :0 :rab] "value")
        (apply-update [:a :c :6 :rab] "value")) => valid?

      (-> document
        (apply-update [:a :b :aa] "value")
        (apply-update [:a :b :ab] "value")
        (apply-update [:a :c :0 :rab] "value")
        (apply-update [:a :d :0 :d2 :0 :od1] "value")) => missing-required-fields?

      (-> document
        (apply-update [:a :b :aa] "value")
        (apply-update [:a :b :ab] "value")
        (apply-update [:a :c :0 :rab] "value")
        (apply-update [:a :d :0 :d2 :0 :od1] "value")
        (apply-update [:a :d :0 :d2 :0 :od2] "value")) => missing-required-fields?

      (-> document
        (apply-update [:a :b :aa] "value")
        (apply-update [:a :b :ab] "value")
        (apply-update [:a :c :0 :rab] "value")
        (apply-update [:a :d :0 :d2 :0 :od1] "value")
        (apply-update [:a :d :0 :d2 :0 :rd] "value")
        (apply-update [:a :d :0 :d2 :0 :od2] "value")) => valid?

      (-> document
        (apply-update [:a :b :aa] "value")
        (apply-update [:a :b :ab] "value")
        (apply-update [:a :c :0 :rab] "value")
        (apply-update [:a :d :0 :d2 :0 :od1] "value")
        (apply-update [:a :d :0 :d2 :0 :rd] "value")
        (apply-update [:a :d :1 :d2 :6 :rd] "value")) => valid?)))

(facts "with real schemas - required fields for henkilo hakija"
  (with-timestamp some-time
    (let [document (-> (new-document (schemas/get-schema "hakija") ..now..)
                   (apply-update [:_selected] "henkilo")
                   (apply-update [:henkilo :henkilotiedot :etunimi] "Tauno")
                   (apply-update [:henkilo :henkilotiedot :sukunimi] "Palo")
                   (apply-update [:henkilo :henkilotiedot :hetu] "230470-658B")
                   (apply-update [:henkilo :osoite :katu] "katu")
                   (apply-update [:henkilo :osoite :postinumero] "12345")
                   (apply-update [:henkilo :osoite :postitoimipaikannimi] "Demola")
                   (apply-update [:henkilo :yhteystiedot :email] "tauno@example.com")
                   (apply-update [:henkilo :yhteystiedot :puhelin] "050"))]

      document => valid?
      (-> document
        (apply-update [:_selected])) => valid?
      (-> document
        (apply-update [:henkilo :osoite :katu])) => missing-required-fields?
      (-> document
        (apply-update [:henkilo :osoite :postinumero])) => missing-required-fields?
      (-> document
        (apply-update [:henkilo :osoite :postitoimipaikannimi])) => missing-required-fields?)))

(facts "with real schemas - required fields for yritys hakija"
  (with-timestamp some-time
    (let [document (-> (new-document (schemas/get-schema "hakija") ..now..)
                     (apply-update [:_selected] "yritys")
                     (apply-update [:yritys :yritysnimi] "Solita")
                     (apply-update [:yritys :liikeJaYhteisoTunnus] "1060155-5")
                     (apply-update [:yritys :osoite :katu] "Satakunnankatu 18 A")
                     (apply-update [:yritys :osoite :postinumero] "33720")
                     (apply-update [:yritys :osoite :postitoimipaikannimi] "Tampere")
                     (apply-update [:yritys :yhteyshenkilo :henkilotiedot :etunimi] "Tauno")
                     (apply-update [:yritys :yhteyshenkilo :henkilotiedot :sukunimi] "Palo")
                     (apply-update [:yritys :yhteyshenkilo :yhteystiedot :email] "tauno@example.com")
                     (apply-update [:yritys :yhteyshenkilo  :yhteystiedot :puhelin] "050"))]
      document => valid?
      (-> document
        (apply-update [:yritys :osoite :katu])) => missing-required-fields?
      (-> document
        (apply-update [:yritys :osoite :postinumero])) => missing-required-fields?
      (-> document
        (apply-update [:yritys :osoite :postitoimipaikannimi])) => missing-required-fields?)))


(fact "apply-approval"
  (apply-approval {} nil ..status.. {:id ..id.. :firstName ..fn.. :lastName ..ln..} ..now..)
  =>
  {:meta {:_approved {:value ..status..
                      :user {:id ..id.. :firstName ..fn.. :lastName ..ln..}
                      :timestamp ..now..}}}
  (apply-approval {} [] ..status.. {:id ..id.. :firstName ..fn.. :lastName ..ln..} ..now..)
  =>
  {:meta {:_approved {:value ..status..
                      :user {:id ..id.. :firstName ..fn.. :lastName ..ln..}
                      :timestamp ..now..}}}
  (apply-approval {} [:a] ..status.. {:id ..id.. :firstName ..fn.. :lastName ..ln..} ..now..)
  =>
  {:meta {:a {:_approved {:value ..status..
                          :user {:id ..id.. :firstName ..fn.. :lastName ..ln..}
                          :timestamp ..now..}}}}
  (apply-approval {} [:a :b] ..status.. {:id ..id.. :firstName ..fn.. :lastName ..ln..} ..now..)
  =>
  {:meta {:a {:b {:_approved {:value ..status..
                              :user {:id ..id.. :firstName ..fn.. :lastName ..ln..}
                              :timestamp ..now..}}}}})

(def approvable-schema {:info {:name "approval-model" :version 1 :approvable true}
                        :body [{:name "s" :type :string}]})

(facts "approvable schema"
  (approvable? (new-document approvable-schema ..now..) []))

(facts "non-approvable schema"
  (approvable? nil) => false
  (approvable? {}) => false
  (approvable? (new-document schema ..now..)) => false)

(def schema-with-approvals {:info {:name "approval-model" :version 1}
                            :body [{:name "single" :type :string :approvable true}
                                   {:name "single2" :type :string}
                                   {:name "repeats" :type :group :repeating true :approvable true
                                    :body [{:name "single3" :type :string}]}]})

(facts "approve document part"
  (let [document (new-document schema-with-approvals ..now..)]
    (approvable? document [:single]) => true
    (approvable? document [:single2]) => false
    (approvable? document [:repeats :1]) => true
    (approvable? document [:repeats :0 :single3]) => false))

(facts "modifications-since-approvals"
  (with-timestamp 10
    (modifications-since-approvals nil) => 0
    (modifications-since-approvals {}) => 0
    (let [base-doc (-> (new-document schema-with-approvals 0)
                   (apply-update [:single] "''"))]
      (modifications-since-approvals base-doc) => 1
      (modifications-since-approvals
        (apply-approval base-doc [:single] "approved" {} 9)) => 1
      (modifications-since-approvals
        (apply-approval base-doc [:single] "approved" {} 10)) => 0
      (modifications-since-approvals
        (apply-approval base-doc [] "approved" {} 9)) => 1
      (modifications-since-approvals
        (apply-approval base-doc [] "approved" {} 10)) => 0
      (modifications-since-approvals
        (-> base-doc
          (assoc-in [:schema :info :approvable] true)
          (apply-approval [] "approved" {} 9)
          (apply-update [:single2] "")
          (apply-update [:repeats :0 :single3] "")
          (apply-update [:repeats :1 :single3] ""))) => 4
      (modifications-since-approvals
        (-> base-doc
          (assoc-in [:schema :info :approvable] true)
          (apply-approval [] "approved" {} 11)
          (apply-update [:single2] "")
          (apply-update [:repeats :0 :single3] "")
          (apply-update [:repeats :1 :single3] ""))) => 0
      (modifications-since-approvals
        (-> base-doc
          (dissoc :data)
          (apply-approval [:repeats :0] "approved" {} 9)
          (apply-update [:single2] "")
          (apply-update [:repeats :0 :single3] "")
          (apply-update [:repeats :1 :single3] ""))) => 2
      (modifications-since-approvals
        (-> base-doc
          (dissoc :data)
          (apply-approval [:repeats :0] "approved" {} 11)
          (apply-update [:single2] "")
          (apply-update [:repeats :0 :single3] "")
          (apply-update [:repeats :1 :single3] ""))) => 1))

  (let [real-doc {:data {:huoneistot {:0 {:huoneistoTunnus {:huoneistonumero {:value "001"}}}}
                         :kaytto {:kayttotarkoitus {:value "011 yhden asunnon talot"}}
                         :rakennuksenOmistajat {:0 {:henkilo {:henkilotiedot {:etunimi {:modified 1370856477455, :value "Pena"}
                                                                              :sukunimi {:modified 1370856477455, :value "Panaani"}}
                                                              :osoite {:katu {:modified 1370856477455, :value "Paapankuja 12"}
                                                                       :postinumero {:value "10203", :modified 1370856487304}
                                                                       :postitoimipaikannimi {:modified 1370856477455, :value "Piippola"}}
                                                              :userId {:value "777777777777777777000020", :modified 1370856477473}
                                                              :yhteystiedot {:email {:modified 1370856477455, :value "pena@example.com"}
                                                                             :puhelin {:modified 1370856477455, :value "0102030405"}}}}}},
                  :meta {:rakennuksenOmistajat {:0 {:_approved {:value "rejected"
                                                                :user {:lastName "Sibbo", :firstName "Sonja", :id "777777777777777777000023"}
                                                                :timestamp 1370856511356}}}}
                  :schema {:info {:approvable true, :op {:id "51b59c112438736b8f1b9d0d", :name "asuinrakennus", :created 1370856465069}, :name "uusiRakennus", :removable true}, :body (:body (schemas/get-schema "uusiRakennus"))}}]
    (modifications-since-approvals real-doc) => 0))

;;
;; Updates
;;

(facts "updating document"
  (with-timestamp some-time
    (fact "single value"
      (apply-update  {} [:b :c] "kikka") => {:data {:b {:c {:value "kikka" :modified some-time}}}})

    (fact "unsetting value"
      (-> {}
        (apply-update [:b :c] "kikka")
        (apply-update [:b :c])) => {:data {:b {:c {:value "" :modified some-time}}}})

    (fact "updates"
      (apply-updates {} [[[:b :c] "kikka"]
                         [[:b :d] "kukka"]]) => {:data {:b {:c {:value "kikka" :modified some-time}
                                                            :d {:value "kukka" :modified some-time}}}})
    (fact "update a map value"
      (apply-update {} [:a :b] {:c 1 :d {:e 2}}) => {:data {:a {:b {:c {:value 1 :modified some-time}
                                                                    :d {:e {:value 2 :modified some-time}}}}}})))

(fact "map2updates"
  (map2updates [:a :b] {:c 1 :d {:e 2}}) => (just [[[:a :b :c] 1]
                                                   [[:a :b :d :e] 2]] :in-any-order))
