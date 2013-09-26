(ns lupapalvelu.security-test
  (:require [midje.sweet :refer :all]
            [lupapalvelu.security :refer :all]))

(fact random-password
  (random-password) => #"[a-zA-Z0-9]{40}")

(fact check-password
  (check-password "foobar" (get-hash "foobar" (dispense-salt))) => truthy
  (check-password "foobar" (get-hash "foobaz" (dispense-salt))) => falsey)
