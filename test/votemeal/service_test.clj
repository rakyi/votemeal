(ns votemeal.service-test
  (:require [clojure.test :refer :all]
            [votemeal.service :as service]))

(deftest valid-args->score
  (is (=
       (service/args->scores ["a" "1" "1" "0" "test" "2"])
       {"a" 1 "1" 0 "test" 2})))

(deftest no-args->score
  (is (=
       (service/args->scores [])
       {})))

(deftest odd-args->score
  (is (thrown?
       Exception
       (service/args->scores ["a" "1" "1" "0" "test"]))))

(deftest invalid-args->score
  (is (thrown?
       Exception
       (service/args->scores ["a" "1" "1" "0" "test" "str"]))))
