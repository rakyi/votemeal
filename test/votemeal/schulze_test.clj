(ns votemeal.schulze-test
  (:require [clojure.test :refer [deftest is]]
            [votemeal.schulze :as schulze]))

(deftest winners
  ;; Example taken from https://en.wikipedia.org/wiki/Schulze_method
  (is (=
       [[\e] [\a] [\c] [\b] [\d]]
       (schulze/winners #{\a \b \c \d \e} {[[\a] [\c] [\b] [\e] [\d]] 5
                                           [[\a] [\d] [\e] [\c] [\b]] 5
                                           [[\b] [\e] [\d] [\a] [\c]] 8
                                           [[\c] [\a] [\b] [\e] [\d]] 3
                                           [[\c] [\a] [\e] [\b] [\d]] 7
                                           [[\c] [\b] [\a] [\d] [\e]] 2
                                           [[\d] [\c] [\e] [\b] [\a]] 7
                                           [[\e] [\b] [\a] [\d] [\c]] 8})))
  (is (=
       [[\d] [\a \b \c] [\e]]
       (schulze/winners #{\a \b \c \d \e} {[[\a] [\b] [\c] [\d] [\e]] 1
                                           [[\b] [\c] [\a] [\d] [\e]] 1
                                           [[\c] [\a] [\b] [\d] [\e]] 1
                                           [[\d] [\c \a \b] [\e]] 4})))
  (is (=
       [[\b] [\a \c]]
       (schulze/winners #{\a \b \c} {[[\a \b] [\c]] 1
                                     [[\c] [\a \b]] 1
                                     [[\b] [\c \a]] 1})))
  (is (=
       [[\a \b \c]]
       (schulze/winners #{\a \b \c} nil))))
