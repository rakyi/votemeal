(ns votemeal.service-test
  (:require [clojure.test :refer :all]
            [votemeal.service :as service]))

(deftest valid-args->score
  (is (=
       (service/args->scores ["a" "1" "1" "0" "test" "2"])
       {"a" 1 "1" 0 "test" 2})))

(deftest odd-args->score
  (is (=
       (service/args->scores ["a" "1" "1" "0" "test"])
       {"a" 1 "1" 0})))

(deftest invalid-args->score
  (is (thrown?
       Exception
       (service/args->scores ["a" "1" "1" "0" "test" "str"]))))
