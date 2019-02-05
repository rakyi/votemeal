;; https://en.wikipedia.org/wiki/Schulze_method
(ns votemeal.schulze
  (:require [clojure.math.combinatorics :as combo]))

(defn preference-pairs [[preferred-seq & others]]
  (lazy-cat (for [preferred preferred-seq
                  other (flatten others)]
              [preferred other])
            (some-> others preference-pairs)))

(defn compute-preferences [weighted-rankings]
  (apply merge-with +
         (map (fn [[ranking weight]]
                (zipmap (preference-pairs ranking) (repeat weight)))
              weighted-rankings)))

(defn init-paths [candidates preferences]
  (into {} (for [[x y :as path] (combo/selections candidates 2)
                 :when (not= x y)
                 :let [xy (get preferences [x y] 0)
                       yx (get preferences [y x] 0)
                       strength (if (> xy yx) xy 0)]]
             [path strength])))

(defn strongest-paths [candidates preferences]
  (reduce (fn [paths [x y z]]
            (update paths [x y] max (min (paths [x z]) (paths [z y]))))
          (init-paths candidates preferences)
          (filter #(apply distinct? %) (combo/selections candidates 3))))

(defn winners
  "Takes a collection of candidates and map of their weighted rankings and
  returns candidates ordered by their preference. Ranking is a sequence of
  sequences of candidates. Candidates in the same inner sequence are treated as
  having the same preference. Each ranking must contain all the candidates."
  [candidates weighted-rankings]
  (let [paths (->> weighted-rankings
                   compute-preferences
                   (strongest-paths (seq candidates)))
        path-comp (fn [a b] (> (paths [a b]) (paths [b a])))]
    (sort path-comp candidates)))  ;; We ignore ties.
