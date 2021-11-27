(ns conspiracy.events
  (:require [re-frame.core :as rf]
            [conspiracy.rules :as rules]))

(rf/reg-event-db
  ::initialize
  (fn [_db [_ {:keys [players]}]]
    {:player-index 0 :game-state (rules/init-game players)}))

(rf/reg-event-db
  ::nominate
  (fn [db [_ active-player-index nominated-player-index]]
    (update db :game-state rules/nominate active-player-index nominated-player-index)))

(rf/reg-event-db
  ::cast-public-vote
  (fn [db [_ player-index pass-or-fail]]
    (update db :game-state rules/cast-public-vote player-index pass-or-fail)))

(rf/reg-event-db
  ::cast-secret-vote
  (fn [db [_ player-index pass-or-fail]]
    (update db :game-state rules/cast-secret-vote player-index pass-or-fail)))

(rf/reg-event-db
  ::assassinate
  (fn [db [_ player-index nominee-index]]
    (update db :game-state rules/assassinate player-index nominee-index)))

(rf/reg-event-db
  ::set-state
  (fn [_db [_ state]]
    state))