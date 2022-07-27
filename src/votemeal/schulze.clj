;; https://en.wikipedia.org/wiki/Schulze_method
(ns votemeal.schulze
  (:require [clojure.math.combinatorics :as combo]))

(defn preference-pairs [[preferred-seq & others]]
  (concat (for [preferred preferred-seq
                other (flatten others)]
            [preferred other])
          (some-> others preference-pairs)))

(defn compute-preferences [weighted-rankings]
  (apply merge-with +
         (map (fn [[ranking weight]]
                (zipmap (preference-pairs ranking) (repeat weight)))
              weighted-rankings)))

(defn init-paths [candidates preferences]
  (reduce (fn [paths [x y :as path]]
            (let [xy (get preferences [x y] 0)
                  yx (get preferences [y x] 0)
                  strength (if (> xy yx) xy 0)]
              (assoc paths path strength)))
          {}
          (combo/permuted-combinations candidates 2)))

(defn strongest-paths [candidates preferences]
  (reduce (fn [paths [x y z]]
            (update paths [x y] max (min (paths [x z]) (paths [z y]))))
          (init-paths candidates preferences)
          (combo/permuted-combinations candidates 3)))

(defn group-ties
  "Resolves ties in sorted list of candidates by grouping them to vectors
  (ranks) by their mutual path strength."
  [candidates paths]
  (reduce (fn [ranks current]
            (let [rank (peek ranks)
                  previous (peek rank)]
              (if (= (paths [current previous]) (paths [previous current]))
                (conj (pop ranks) (conj rank current))  ; add to same rank
                (conj ranks [current]))))               ; start a new rank
          [[(first candidates)]]
          (rest candidates)))

(defn winners
  "Takes a collection of candidates and map of their weighted rankings. Ranking
  is a sequence of sequences of candidates. Candidates in the same inner
  sequence are treated as having the same preference. Each ranking must contain
  all the candidates.

  Returns vector of vectors of candidates ordered by their preference. Multiple
  candidates in the same inner vector means a tie."
  [candidates weighted-rankings]
  (let [preferences (compute-preferences weighted-rankings)
        paths (strongest-paths (seq candidates) preferences)
        path-comp (fn [a b] (> (paths [a b]) (paths [b a])))]
    (group-ties (sort path-comp candidates) paths)))
