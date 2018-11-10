(ns votemeal.machine)

(defn vote! [db user-id scores]
  (swap! db update :poll assoc user-id scores))

(defn update-user! [db user]
  (swap! db update :users assoc (:id user) user))

(defn close! [db]
  (let [ballots (:poll @db)
        cnt (count ballots)]
    (reset! db {})
    {:scores (->> ballots
                  vals
                  (apply merge-with +)
                  (map (fn [[place score]]
                         [place (/ score cnt)]))
                  (into {}))
     :count cnt}))
