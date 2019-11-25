(ns conspiracy.rules)

(def max-votes 5)

(def player-mats
  {5  {:secret-vote-sizes [2 3 2 3 3] :overthrow-votes [1 1 1 1 1] :evil 2}
   6  {:secret-vote-sizes [2 3 4 3 4] :overthrow-votes [1 1 1 1 1] :evil 2}
   7  {:secret-vote-sizes [2 3 3 4 4] :overthrow-votes [1 1 1 2 1] :evil 3}
   8  {:secret-vote-sizes [3 4 4 5 5] :overthrow-votes [1 1 1 2 1] :evil 3}
   9  {:secret-vote-sizes [3 4 4 5 5] :overthrow-votes [1 1 1 2 1] :evil 3}
   10 {:secret-vote-sizes [3 4 4 5 5] :overthrow-votes [1 1 1 2 1] :evil 4}})

(def good-role-seq (cons :spymaster (repeat :spy)))
(def evil-role-seq (cons :assassin (repeat :traitor)))

(defn stage [{:keys [players results nominees] :as game-state}]
  (let [num-nominees (get-in player-mats [(count players) :secret-vote-sizes (count results)])
        {:keys [pass fail]} (frequencies results)]
    (cond
      (= 3 fail) :evil-wins
      (= 3 pass) :good-wins
      (< (count nominees) num-nominees) :nomination
      (not-every? :vote players) :public-vote
      (< (count (map :secret-vote players)) num-nominees) :private-vote)))

(defn init-game [players]
  (let [{:keys [evil]} (player-mats (count players))
        good (- (count players) evil)
        roles (shuffle
                (into
                  (take good good-role-seq)
                  (take evil evil-role-seq)))]
    {:players              (mapv (fn [player role] (assoc player :role role)) players roles)
     :current-player-index 0
     :nominees             #{}}))

(defn mission-size [{:keys [players results] :as game-state}]
  (get-in player-mats [(count players) :secret-vote-sizes (count results)]))

(defn num-votes-to-fail [{:keys [players results] :as game-state}]
  (get-in player-mats [(count players) :overthrow-votes (count results)]))

(defn cycle-president [{:keys [players] :as game-state}]
  (update game-state :current-player-index (fn [i] (mod (inc i) (count players)))))

(defn reset-votes [game-state]
  (update game-state :players (fn [players] (mapv #(dissoc % :vote :secret-vote) players))))

(defn reset-nominees [game-state]
  (update game-state :nominees empty))

(defn reset-government [game-state]
  (-> game-state cycle-president reset-nominees reset-votes))

(defn update-vote-track [game-state value]
  (-> game-state
      (update :vote-track (comp vec conj) value)))

(defn pass-vote [game-state]
  (update-vote-track game-state :passed))

(defn fail-vote [game-state]
  (-> game-state
      (update-vote-track :failed)
      reset-government))

(defn resolve-mission [game-state value]
  (-> game-state
      (update :results (comp vec conj) value)
      (update :vote-track empty)
      reset-government))

(defn pass-mission [game-state]
  (print "The mission passed")
  (resolve-mission game-state :pass))

(defn fail-mission [game-state]
  (resolve-mission game-state :failed))

(defn public-vote? [{:keys [nominees] :as game-state}]
  (= (count nominees) (mission-size game-state)))

(defn nominate
  [{:keys [current-player-index nominees] :as game-state} player-index nominee-index]
  (cond-> game-state
          (and (= player-index current-player-index)
               (< (count nominees) (mission-size game-state)))
          (update :nominees conj nominee-index)))

(defn resolve-public-vote
  [{:keys [players vote-track] :as game-state}]
  (if (every? :vote players)
    (let [{:keys [pass fail]} (frequencies (map :vote players))]
      (cond
        (> pass fail) (pass-vote game-state)
        (= max-votes (count vote-track)) (fail-mission game-state)
        :else (fail-vote game-state)))
    game-state))

(defn cast-public-vote [{:keys [nominees] :as game-state} player-index vote]
  (if (= (count nominees) (mission-size game-state))
    (resolve-public-vote
      (assoc-in game-state [:players player-index :vote] vote))
    game-state))

(defn resolve-secret-vote [{:keys [players nominees] :as game-state}]
  (let [required-votes (mission-size game-state)]
    (if (= (count nominees) required-votes)
      (let [votes (filter :secret-vote players)]
        (if (= (count votes) required-votes)
          (if (>= (count (filter (comp #{:fail} :secret-vote) votes))
                  (num-votes-to-fail game-state))
            (fail-mission game-state)
            (pass-mission game-state))
          game-state))
      game-state)))

(defn cast-secret-vote
  [{:keys [nominees] :as game-state} player-index vote]
  (resolve-secret-vote
    (cond-> game-state
            (nominees player-index)
            (assoc-in [:players player-index :secret-vote] vote))))

(comment
  (def players
    [{:name "Mark"}
     {:name "Bob"}
     {:name "Becky"}
     {:name "Sue"}
     {:name "Sam"}
     {:name "Jill"}])

  ;Nominate 2 players
  (-> players
      init-game
      (nominate 0 0)
      (nominate 0 2)
      (cast-public-vote 0 :pass)
      (cast-public-vote 3 :pass)
      (cast-public-vote 4 :fail)
      (cast-public-vote 5 :pass)
      (cast-public-vote 1 :fail)
      (cast-public-vote 2 :pass)
      (cast-secret-vote 0 :fail)
      ;(cast-secret-vote 2 :pass)
      )

  (-> players
      init-game
      (nominate 0 1)
      (nominate 0 2)
      (cast-public-vote 0 :pass)
      (cast-public-vote 3 :pass)
      (cast-public-vote 4 :fail)
      (cast-public-vote 5 :fail)
      (cast-public-vote 1 :fail)
      (cast-public-vote 2 :pass))
  )
