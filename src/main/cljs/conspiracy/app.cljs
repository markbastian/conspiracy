;(shadow.cljs.devtools.api/nrepl-select :app)
(ns conspiracy.app
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as r]
            [reagent.dom :as dom]
            [ajax.core :refer [GET POST]]
            [clojure.string :as cs]
            [conspiracy.rules :as rules]
            [clojure.walk :refer [keywordize-keys]]
            [cljs.core.async :as a :refer [<! >!]]
            [clojure.pprint :as pp]
            [conspiracy.api :as api]
            [re-frame.db :as db]
            [re-frame.core :as rf]))

(def players
  [{:name "Mark"}
   {:name "Becky"}
   {:name "Chloe"}
   {:name "Gregor"}
   {:name "Luxa"}
   {:name "Boots"}])

(rf/reg-event-db
  :initialize
  (fn [_ _]
    {:player-index 0
     :game-state   (rules/init-game players)}))

(rf/reg-event-db
  :nominate
  (fn [db [_ active-player-index nominated-player-index]]
    (update db :game-state rules/nominate active-player-index nominated-player-index)))

(rf/reg-event-db
  :cast-public-vote
  (fn [db [_ player-index pass-or-fail]]
    (update db :game-state rules/cast-public-vote player-index pass-or-fail)))

(rf/reg-event-db
  :cast-secret-vote
  (fn [db [_ player-index pass-or-fail]]
    (update db :game-state rules/cast-secret-vote player-index pass-or-fail)))

(rf/reg-event-db
  :assassinate
  (fn [db [_ player-index nominee-index]]
    (update db :game-state rules/assassinate player-index nominee-index)))

(defonce state (r/atom {:player-index 0
                        :game-state   (rules/init-game players)}))
(def warning (r/cursor state [:warning]))

(defn log [m] (.log js/console m))

(defn render-results-track [state]
  (let [results (r/cursor state [:game-state :results])]
    (fn [state]
      [:span.text-center
       (doall
         (for [[i {:keys [pass fail result] :as r} :as k] (map vector (range) @results)]
           ^{:key k} [(if (= :pass result)
                        :button.btn.btn-success
                        :button.btn.btn-danger)
                      {:style {:border-radius :1.5rem}}
                      [:span.far.fa-thumbs-up (or pass 0)]
                      " / "
                      [:span.far.fa-thumbs-down (or fail 0)]]))])))

(defn render-nominations [state stage]
  (let [player-index (get-in @state [:player-index])
        current-player-index (r/cursor state [:game-state :current-player-index])]
    (fn [state stage]
      (when (= stage :nomination)
        (let [message (if (= player-index @current-player-index)
                        (str "Select "
                             (rules/mission-size (get @state :game-state))
                             " players to go on the mission")
                        (str "Waiting for "
                             (get-in @state [:game-state :players @current-player-index :name])
                             " to nominate "
                             (rules/mission-size (get @state :game-state))
                             " players"))]
          [:h4.text-center message])))))

(defn render-public-vote [state stage]
  (let [game-state (r/cursor state [:game-state])
        player-index (:player-index @state)]
    (fn [state stage]
      (when (= stage :public-vote)
        (if (get-in @game-state [:players player-index :vote])
          [:h4.text-center "Your vote has been recorded"]
          [:div
           [:h4.text-center "Should these players go on the mission?"]
           [:div.text-center
            [:button.btn.btn-success
             {:style   {:border-radius :1.5rem}
              :onClick (fn [_]
                         (rf/dispatch [:cast-public-vote player-index :pass])
                         (swap! game-state rules/cast-public-vote player-index :pass))}
             [:span.far.fa-thumbs-up.fa-2x]]
            [:button.btn.btn-danger
             {:style   {:border-radius :1.5rem}
              :onClick (fn [_]
                         (rf/dispatch [:cast-public-vote player-index :fail])
                         (swap! game-state rules/cast-public-vote player-index :fail))}
             [:span.far.fa-thumbs-down.fa-2x]]]])))))

(defn render-private-vote [state stage]
  (let [game-state (r/cursor state [:game-state])
        player-index (:player-index @state)]
    (fn [state stage]
      (when (= stage :private-vote)
        (cond
          (get-in @game-state [:players player-index :secret-vote])
          [:h4.text-center "Your vote has been recorded"]
          (nil? (get-in @game-state [:nominees player-index]))
          [:h4.text-center "Waiting for mission results"]
          :default
          [:div
           [:h4.text-center "Should this mission succeed?"]
           [:div.text-center
            [:button.btn.btn-success
             {:style {:border-radius :1.5rem}
              :onClick
                     (fn [_]
                       (rf/dispatch [:cast-secret-vote player-index :pass])
                       (swap! game-state rules/cast-secret-vote player-index :pass))}
             [:span.far.fa-thumbs-up.fa-2x]]
            [:button.btn.btn-danger
             {:style   {:border-radius :1.5rem}
              :onClick (fn [_]
                         (rf/dispatch [:cast-secret-vote player-index :fail])
                         (swap! game-state rules/cast-secret-vote player-index :fail))}
             [:span.far.fa-thumbs-down.fa-2x]]]])))))

(defn render-assassin-instructions [state stage]
  (let [game-state (r/cursor state [:game-state])
        player-index (:player-index @state)]
    (fn [state stage]
      (when (= stage :assassin-guess)
        (if (= :assassin (get-in @game-state [:players player-index :role]))
          [:h4.text-center "Select a player to assassinate"]
          [:h4.text-center "Waiting for the assassin"])))))

(defn render-active-player [state player-index]
  (let [active-player (r/cursor state [:game-state :current-player-index])]
    (fn [state player-index]
      (when (= @active-player player-index) [:span.fas.fa-crown.text-warning]))))

(def player-button-style
  {;:font-family "Times New Roman"
   :border-radius :1.5rem
   :border-color  "#CCCCCC"})

(defn render-player-nominations [state stage]
  (let [players (r/cursor state [:game-state :players])
        game-state (r/cursor state [:game-state])
        nominees (r/cursor state [:game-state :nominees])
        active-player (r/cursor state [:game-state :current-player-index])]
    (fn [state stage]
      (when (= :nomination stage)
        [:div
         (doall
           (for [[{player-name :name :keys [role vote] :as player} i] (map vector @players (range))]
             ^{:key (assoc player :stage stage)}
             [:button.btn.btn-lg.btn-block
              {:style   (cond-> player-button-style
                                (@nominees i)
                                (assoc :background-color "#E0E0E0"))
               :onClick (fn [_]
                          (rf/dispatch [:nominate @active-player i])
                          (swap! game-state rules/nominate @active-player i))}
              [render-active-player state i]
              player-name]))]))))

(defn render-players-public-vote [state stage]
  (let [players (r/cursor state [:game-state :players])
        game-state (r/cursor state [:game-state])
        nominees (r/cursor state [:game-state :nominees])]
    (fn [state stage]
      (when (= :public-vote stage)
        [:div
         (doall
           (for [[{player-name :name :keys [role vote] :as player} i] (map vector @players (range))]
             ^{:key (assoc player :stage stage)}
             [:button.btn.btn-lg.btn-block
              {:style (cond-> player-button-style
                              (@nominees i)
                              (assoc :background-color "#E0E0E0"))}
              [:span.float-left.far.fa-thumbs-up.text-success
               {:onClick (fn [_]
                           (rf/dispatch [:cast-public-vote i :pass])
                           (swap! game-state rules/cast-public-vote i :pass))}]
              [render-active-player state i]
              player-name
              [:span.float-right.far.fa-thumbs-down.text-danger
               {:onClick (fn [_]
                           (rf/dispatch [:cast-public-vote i :fail])
                           (swap! game-state rules/cast-public-vote i :fail))}]]))]))))

(defn render-players-secret-vote [state stage]
  (let [players (r/cursor state [:game-state :players])
        game-state (r/cursor state [:game-state])
        nominees (r/cursor state [:game-state :nominees])]
    (fn [state stage]
      (when (= :private-vote stage)
        [:div
         (doall
           (for [[{player-name :name :keys [role vote] :as player} i] (map vector @players (range))]
             ^{:key player}
             [:button.btn.btn-lg.btn-block
              {:style (cond-> player-button-style
                              (@nominees i)
                              (assoc :background-color "#E0E0E0"))}
              (when (@nominees i)
                [:span.float-left.far.fa-thumbs-up.text-success
                 {:onClick (fn [_]
                             (rf/dispatch [:cast-secret-vote i :pass])
                             (swap! game-state rules/cast-secret-vote i :pass))}])
              [render-active-player state i]
              player-name
              (when (@nominees i)
                [:span.float-right.far.fa-thumbs-down.text-danger
                 {:onClick (fn [_]
                             (rf/dispatch [:cast-secret-vote i :fail])
                             (swap! game-state rules/cast-secret-vote i :fail))}])]))]))))


(defn render-players-assassin [state stage]
  (let [player-index (:player-index @state)
        players (r/cursor state [:game-state :players])
        game-state (r/cursor state [:game-state])
        nominees (r/cursor state [:game-state :nominees])
        assassin-index (rules/assassin-index @game-state)]
    (fn [state stage]
      (when (and (= :assassin-guess stage)
                 ;(= player-index (rules/assassin-index @game-state))
                 )
        [:div
         (doall
           (for [[{player-name :name :keys [role vote] :as player} i] (map vector @players (range))
                 :when (#{:spy :spymaster} role)]
             ^{:key player}
             [:button.btn.btn-lg.btn-block
              {:style   (cond-> player-button-style
                                (@nominees i)
                                (assoc :background-color "#E0E0E0"))
               :onClick (fn [_]
                          (rf/dispatch [:assassinate assassin-index i])
                          (swap! game-state rules/assassinate assassin-index i))}
              player-name]))]))))

(defn render-players-endgame [state stage]
  (let [players (r/cursor state [:game-state :players])
        game-state (r/cursor state [:game-state])
        nominees (r/cursor state [:game-state :nominees])]
    (fn [state stage]
      (when (#{:evil-wins :good-wins} stage)
        [:div
         (doall
           (for [[{player-name :name :keys [role vote] :as player} i] (map vector @players (range))]
             ^{:key player}
             [:button.btn.btn-lg.btn-block
              {:style (cond-> player-button-style
                              (@nominees i)
                              (assoc :background-color "#E0E0E0"))}
              (when (@nominees i)
                [:span.float-left.far.fa-thumbs-up.text-success
                 {:onClick (fn [_]
                             (rf/dispatch [:cast-secret-vote i :pass])
                             (swap! game-state rules/cast-secret-vote i :pass))}])
              [render-active-player state i]
              (str player-name ":" (name role))
              (when (@nominees i)
                [:span.float-right.far.fa-thumbs-down.text-danger
                 {:onClick (fn [_]
                             (rf/dispatch [:cast-secret-vote i :fail])
                             (swap! game-state rules/cast-secret-vote i :fail))}])]))]))))

(defn render-players [state]
  (let [game-state (r/cursor state [:game-state])]
    (fn [state]
      (let [stage (rules/stage @game-state)]
        [:div
         [render-player-nominations state stage]
         [render-players-public-vote state stage]
         [render-players-secret-vote state stage]
         [render-players-assassin state stage]
         [render-players-endgame state stage]
         [:br]
         [render-nominations state stage]
         [render-public-vote state stage]
         [render-private-vote state stage]
         [render-assassin-instructions state stage]
         [render-results-track state]
         [:p (with-out-str (pp/pprint @game-state))]
         [:p stage]]))))

(defn render-role [state]
  (let [player-index (get-in @state [:player-index])
        {:keys [role]} (get-in @state [:game-state :players player-index])]
    [:h1.text-center (name role)]))

(defn chatroom []
  [:div
   [:h1 "Welcome to Conspiracy!"]
   [:h2 "Enter your name to join:"]])

(defn render [state]
  (let [username (r/atom nil)
        message (r/atom nil)
        socket (r/cursor state [:stream :socket])
        messages (r/cursor state [:messages])]
    (fn [state]
      [:div
       [:h4 "Welcome to Conspiracy!"]
       (if @socket
         [:div.form-group
          [:label "Type a message"]
          [:input.form-control {:type     "text"
                                :onChange (fn [v] (reset! message (-> v .-target .-value)))}]
          [:button.btn.btn-primary {:type    "submit"
                                    :onClick (fn [s] (api/send-message state @message))}
           "Submit"]]
         [:div.form-group
          [:label "Enter your name to join:"]
          [:input.form-control {:type        "text"
                                :placeholder "Name"
                                :onChange    (fn [v] (reset! username (-> v .-target .-value)))}]
          [:button.btn.btn-primary {:type    "submit"
                                    :onClick (fn [s] (api/connect-ws state @username))}
           "Submit"]])
       (let [ms @messages]
         (for [i (range (count ms)) :let [{:keys [time sender message]} (ms i)]]
           [:span
            {:key (str "chat-message-" i)}
            [:span.alert.alert-primary.text-center {:role "alert"}
             time]
            [:span.alert.alert-primary.text-right {:role "alert"}
             message]]))
       ]))
  [:div
   [:ul.nav.nav-tabs.nav-fill.tabs-fixed-top
    [:li.nav-item [:a.nav-link.active {:data-toggle "tab" :href "#players"}
                   [:span.fas.fa-users.fa-2x]]]
    [:li.nav-item [:a.nav-link {:data-toggle "tab" :href "#public-votes"}
                   [:span.fas.fa-poll.fa-2x]]]
    [:li.nav-item [:a.nav-link {:data-toggle "tab" :href "#private-votes"}
                   [:span.fas.fa-user-secret.fa-2x]]]
    [:li.nav-item [:a.nav-link {:data-toggle "tab" :href "#role"}
                   [:span.fas.fa-info-circle.fa-2x]]]]
   [:div.tab-content
    [:div#players.tab-pane.container-fluid.active
     [render-players state]]
    [:div#public-votes.tab-pane.fade [render-players state]]
    [:div#private-votes.tab-pane.fade [render-players state]]
    [:div#role.tab-pane.fade [render-role state]]]])

(defn ^:dev/after-load ui-root []
  (dom/render [render state] (.getElementById js/document "ui-root")))

(defn init []
  (let [root (.getElementById js/document "ui-root")]
    (.log js/console root)
    (dom/render [render state] root)))

(comment
  )

