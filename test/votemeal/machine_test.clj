(ns votemeal.machine-test
  (:require [clojure.test :refer :all]
            [votemeal.machine :as machine])
  (:import [java.time LocalDate]))

(deftest close!
  (let [db (atom {(LocalDate/now) {"user-1" {"a" 1 "b" 0 "c" 2 "d" 2}
                                   "user-2" {"b" 1 "c" 1 "d" 2}}})]
    (is (=
          (machine/close! db)
          {:scores {"a" 1/2 "b" 1/2 "c" 3/2 "d" 2}
           :count 2}))
    (is (= @db {}))))
