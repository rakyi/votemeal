(ns votemeal.machine
  (:require [votemeal.schulze :as schulze]))

(defn new! [db candidates]
  (reset! db {:poll {:candidates candidates}}))

(defn vote! [db user-id ballot]
  (swap! db update-in [:poll :ballots] assoc user-id ballot))

(defn add-user! [db user]
  (swap! db update :users assoc (:id user) user))

(defn close! [db]
  (let [{:keys [ballots candidates]} (:poll @db)]
    (reset! db nil)
    {:winners (->> ballots
                   (vals)
                   (frequencies)
                   (schulze/winners candidates))
     :count (count ballots)}))
