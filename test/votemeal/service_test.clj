(ns votemeal.service-test
  (:require [clojure.test :refer [deftest is]]
            [votemeal.service :as service])
  (:import [clojure.lang ExceptionInfo]))

(def candidates #{"a" "b" "c" "d"})

(deftest valid-ballot
  (is (=
       [["a"] ["b" "c"] ["d"]]
       (service/make-ballot candidates "a, b c, d")))
  (is (=
       [["a"] ["b" "c"] ["d"]]
       (service/make-ballot candidates "a,b  c  ,  d"))))

(deftest no-args-ballot
  (is (= [(vec candidates)] (service/make-ballot candidates nil))))

(deftest unknown-candidate-ballot
  (is (thrown? ExceptionInfo (service/make-ballot candidates "a, b c, e"))))

(deftest duplicate-candidate-ballot
  (is (thrown? ExceptionInfo (service/make-ballot candidates "a, b a, c"))))
