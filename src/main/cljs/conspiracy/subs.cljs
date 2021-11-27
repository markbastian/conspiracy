(ns conspiracy.subs
  (:require [re-frame.core :as rf]
            [conspiracy.rules :as rules]))

(rf/reg-sub
  ::player-index
  (fn [{:keys [player-index]} _] player-index))

(rf/reg-sub
  ::game-state
  (fn [db _] (-> db :game-state)))

(rf/reg-sub
  ::players
  (fn [db _] (-> db :game-state :players)))

(rf/reg-sub
  ::nominees
  (fn [db _] (-> db :game-state :nominees)))

(rf/reg-sub
  ::assassin-index
  (fn [db _] (-> db :game-state rules/assassin-index)))

(rf/reg-sub
  ::stage
  (fn [db _] (-> db :game-state rules/stage)))

(rf/reg-sub
  ::current-player-index
  (fn [db _] (-> db :game-state :current-player-index)))

(rf/reg-sub
  ::game-results
  (fn [db _] (-> db :game-state :results)))