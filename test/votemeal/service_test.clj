(ns votemeal.service-test
  (:require [clojure.test :refer :all]
            [votemeal.service :as service])
  (:import [clojure.lang ExceptionInfo]))

(def candidates #{"a" "b" "c" "d"})

(deftest valid-ballot
  (is (=
       (service/ballot candidates "a, b c, d")
       [["a"] ["b" "c"] ["d"]])))

(deftest no-args-ballot
  (is (=
       (service/ballot candidates nil)
       [(vec candidates)])))

(deftest unknown-candidate-ballot
  (is (thrown?
       ExceptionInfo
       (service/ballot candidates "a, b c, e"))))

(deftest duplicate-candidate-ballot
  (is (thrown?
       ExceptionInfo
       (service/ballot candidates "a, b a, c"))))
