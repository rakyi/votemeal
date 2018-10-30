(ns votemeal.machine
  (:import [java.time LocalDate]))

(defn vote! [db user-id scores]
  (swap! db update (LocalDate/now) assoc user-id scores))

(defn close! [db]
  (let [ballots (get @db (LocalDate/now))]
    (reset! db {})
    {:scores (->> ballots
                  vals
                  (apply merge-with +)
                  (map (fn [[place score]]
                          [place (/ score (count ballots))]))
                  (into {}))
     :count (count ballots)}))
