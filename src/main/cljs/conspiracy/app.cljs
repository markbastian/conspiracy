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
  ::initialize
  (fn [_ _]
    {:player-index 0
     :game-state   (rules/init-game players)}))

(rf/reg-event-db
  ::nominate
  (fn [db [_ active-player-index nominated-player-index]]
    (doto
      (update db :game-state rules/nominate active-player-index nominated-player-index)
      pp/pprint)))

(rf/reg-event-db
  ::cast-public-vote
  (fn [db [_ player-index pass-or-fail]]
    (doto
      (update db :game-state rules/cast-public-vote player-index pass-or-fail)
      pp/pprint)))

(rf/reg-event-db
  ::cast-secret-vote
  (fn [db [_ player-index pass-or-fail]]
    (doto
      (update db :game-state rules/cast-secret-vote player-index pass-or-fail)
      pp/pprint)))

(rf/reg-event-db
  ::assassinate
  (fn [db [_ player-index nominee-index]]
    (doto
      (update db :game-state rules/assassinate player-index nominee-index))
    pp/pprint))

(rf/reg-event-db
  ::set-state
  (fn [_db [_ state]]
    state))

(comment
  (rf/dispatch
    [::set-state
     {:player-index 0
      :game-state
      {:players
       [{:name "Mark" :role :assassin}
        {:name "Becky" :role :spy}
        {:name "Chloe" :role :traitor}
        {:name "Gregor" :role :spymaster}
        {:name "Luxa" :role :spy}
        {:name "Boots" :role :spy}]
       :current-player-index 3
       :nominees #{}
       :vote-track []
       :results
       [{:pass 2 :result :pass}
        {:pass 3 :result :pass}
        {:pass 4 :result :pass}]}}])
  )

(defn log [m] (.log js/console m))

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

(defn render-results-track []
  (let [results (rf/subscribe [::game-results])]
    [:span.text-center
     (doall
       (for [[i {:keys [pass fail result] :as r} :as k] (map vector (range) @results)]
         ^{:key k} [(if (= :pass result)
                      :button.btn.btn-success
                      :button.btn.btn-danger)
                    {:style {:border-radius :1.5rem}}
                    [:span.far.fa-thumbs-up (or pass 0)]
                    " / "
                    [:span.far.fa-thumbs-down (or fail 0)]]))]))

(defn render-nominations []
  (let [player-index (rf/subscribe [::player-index])
        current-player-index (rf/subscribe [::current-player-index])
        stage (rf/subscribe [::stage])
        game-state (rf/subscribe [::game-state])]
    (when (= @stage :nomination)
      (let [message (if (= player-index current-player-index)
                      (str "Select "
                           (rules/mission-size game-state)
                           " players to go on the mission")
                      (str "Waiting for "
                           (get-in game-state [:players current-player-index :name])
                           " to nominate "
                           (rules/mission-size game-state)
                           " players"))]
        [:h4.text-center message]))))

(defn render-public-vote []
  (let [game-state (rf/subscribe [::game-state])
        player-index (rf/subscribe [::player-index])
        stage (rf/subscribe [::stage])]
    (when (= @stage :public-vote)
      (if (get-in @game-state [:players player-index :vote])
        [:h4.text-center "Your vote has been recorded"]
        [:div
         [:h4.text-center "Should these players go on the mission?"]
         [:div.text-center
          [:button.btn.btn-success
           {:style   {:border-radius :1.5rem}
            :onClick #(rf/dispatch [::cast-public-vote player-index :pass])}
           [:span.far.fa-thumbs-up.fa-2x]]
          [:button.btn.btn-danger
           {:style   {:border-radius :1.5rem}
            :onClick #(rf/dispatch [::cast-public-vote player-index :fail])}
           [:span.far.fa-thumbs-down.fa-2x]]]]))))

(defn render-private-vote []
  (let [game-state (rf/subscribe [::game-state])
        player-index (rf/subscribe [::player-index])
        stage (rf/subscribe [::stage])]
    (when (= @stage :private-vote)
      (cond
        (get-in @game-state [:players player-index :secret-vote])
        [:h4.text-center "Your vote has been recorded"]
        (nil? (get-in game-state [:nominees player-index]))
        [:h4.text-center "Waiting for mission results"]
        :default
        [:div
         [:h4.text-center "Should this mission succeed?"]
         [:div.text-center
          [:button.btn.btn-success
           {:style   {:border-radius :1.5rem}
            :onClick #(rf/dispatch [::cast-secret-vote player-index :pass])}
           [:span.far.fa-thumbs-up.fa-2x]]
          [:button.btn.btn-danger
           {:style   {:border-radius :1.5rem}
            :onClick #(rf/dispatch [::cast-secret-vote player-index :fail])}
           [:span.far.fa-thumbs-down.fa-2x]]]]))))

(defn render-assassin-instructions []
  (let [players (rf/subscribe [::players])
        player-index (rf/subscribe [::player-index])
        stage (rf/subscribe [::stage])]
    (when (= @stage :assassin-guess)
      (if (= :assassin (get-in players [player-index :role]))
        [:h4.text-center "Select a player to assassinate"]
        [:h4.text-center "Waiting for the assassin"]))))

(defn render-active-player [player-index]
  (let [active-player (rf/subscribe [::current-player-index])]
    (when (= @active-player player-index) [:span.fas.fa-crown.text-warning])))

(def player-button-style
  {;:font-family "Times New Roman"
   :border-radius :1.5rem
   :border-color  "#CCCCCC"})

(defn render-player-nominations []
  (let [players (rf/subscribe [::players])
        nominees (rf/subscribe [::nominees])
        active-player (rf/subscribe [::current-player-index])
        stage (rf/subscribe [::stage])]
    (when (= :nomination @stage)
      [:div
       (doall
         (for [[{player-name :name :keys [role vote] :as player} i]
               (map vector @players (range))]
           ^{:key (assoc player :stage @stage)}
           [:button.btn.btn-lg.btn-block
            {:style   (cond-> player-button-style
                              (@nominees i)
                              (assoc :background-color "#E0E0E0"))
             :onClick #(rf/dispatch [::nominate @active-player i])}
            [render-active-player i]
            player-name]))])))

(defn render-players-public-vote []
  (let [players (rf/subscribe [::players])
        nominees (rf/subscribe [::nominees])
        stage (rf/subscribe [::stage])]
    (when (= :public-vote @stage)
      [:div
       (doall
         (for [[{player-name :name :keys [role vote] :as player} i] (map vector @players (range))]
           ^{:key (assoc player :stage @stage)}
           [:button.btn.btn-lg.btn-block
            {:style (cond-> player-button-style
                            (get @nominees i)
                            (assoc :background-color "#E0E0E0"))}
            [:span.float-left.far.fa-thumbs-up.text-success
             {:onClick #(rf/dispatch [::cast-public-vote i :pass])}]
            [render-active-player i]
            player-name
            [:span.float-right.far.fa-thumbs-down.text-danger
             {:onClick #(rf/dispatch [::cast-public-vote i :fail])}]]))])))

(defn render-players-secret-vote []
  (let [players (rf/subscribe [::players])
        nominees (rf/subscribe [::nominees])
        stage (rf/subscribe [::stage])]
    (when (= :private-vote @stage)
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
               {:onClick #(rf/dispatch [::cast-secret-vote i :pass])}])
            [render-active-player i]
            player-name
            (when (@nominees i)
              [:span.float-right.far.fa-thumbs-down.text-danger
               {:onClick #(rf/dispatch [::cast-secret-vote i :fail])}])]))])))


(defn render-players-assassin []
  (let [players (rf/subscribe [::players])
        nominees (rf/subscribe [::nominees])
        assassin-index (rf/subscribe [::assassin-index])
        stage (rf/subscribe [::stage])]
    (when (and (= :assassin-guess @stage)
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
             :onClick #(rf/dispatch [::assassinate @assassin-index i])}
            player-name]))])))

(defn render-players-endgame []
  (let [players (rf/subscribe [::players])
        nominees (rf/subscribe [::nominees])
        stage (rf/subscribe [::stage])]
    (when (#{:evil-wins :good-wins} @stage)
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
               {:onClick #(rf/dispatch [::cast-secret-vote i :pass])}])
            [render-active-player i]
            (str player-name ":" (name role))
            (when (@nominees i)
              [:span.float-right.far.fa-thumbs-down.text-danger
               {:onClick #(rf/dispatch [::cast-secret-vote i :fail])}])]))])))

(defn render-players []
  (let [stage (rf/subscribe [::stage])]
    [:div
     [render-player-nominations]
     [render-players-public-vote]
     [render-players-secret-vote]
     [render-players-assassin]
     [render-players-endgame]
     [:br]
     [render-nominations]
     [render-public-vote]
     [render-private-vote]
     [render-assassin-instructions]
     [render-results-track]
     [:h2 @stage]
     ]))

(defn render-role []
  (let [player-index (rf/subscribe [::player-index])
        players (rf/subscribe [::players])]
    (let [{:keys [role]} (get @players @player-index)]
      [:h1.text-center (name role)])))

(defn chatroom []
  [:div
   [:h1 "Welcome to Conspiracy!"]
   [:h2 "Enter your name to join:"]])

(defn render []
  #_(let [username (r/atom nil)
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
     [render-players]]
    [:div#public-votes.tab-pane.fade [render-players]]
    [:div#private-votes.tab-pane.fade [render-players]]
    [:div#role.tab-pane.fade [render-role]]]])

(defn ^:dev/after-load ui-root []
  (rf/dispatch [::initialize])
  (dom/render [render] (.getElementById js/document "ui-root")))

(defn init []
  (rf/dispatch [::initialize])
  (let [root (.getElementById js/document "ui-root")]
    (.log js/console root)
    (dom/render [render] root)))

(comment
  )

