;(shadow.cljs.devtools.api/nrepl-select :app)
(ns conspiracy.app
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.dom :as dom]
            [ajax.core :refer [GET POST]]
            [conspiracy.rules :as rules]
            [clojure.walk :refer [keywordize-keys]]
            [cljs.core.async :as a :refer [<! >!]]
            [re-frame.core :as rf]
            [conspiracy.events :as events]
            [conspiracy.subs :as subs]))

(def players
  [{:name "Mark"}
   {:name "Becky"}
   {:name "Chloe"}
   {:name "Gregor"}
   {:name "Luxa"}
   {:name "Boots"}])

(comment
  (rf/dispatch
    [::events/set-state
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
                     :nominees             #{}
                     :vote-track           []
                     :results
                                           [{:pass 2 :result :pass}
                                            {:pass 3 :result :pass}
                                            {:pass 4 :result :pass}]}}])

  (rf/dispatch
    [::events/set-state
     {:player-index 0,
      :game-state
                    {:players
                                           [{:name "Mark", :role :traitor}
                                            {:name "Becky", :role :spy}
                                            {:name "Chloe", :role :spy}
                                            {:name "Gregor", :role :spymaster}
                                            {:name "Luxa", :role :spy}
                                            {:name "Boots", :role :assassin}],
                     :current-player-index 5,
                     :nominees             #{},
                     :vote-track           [],
                     :results
                                           [{:pass 1, :fail 1, :result :fail}
                                            {:pass 3, :result :pass}
                                            {:pass 4, :result :pass}
                                            {:pass 3, :result :pass}]}}])

  )

(defn log [m] (.log js/console m))

(defn render-results-track []
  (let [results (rf/subscribe [::subs/game-results])]
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
  (let [player-index (rf/subscribe [::subs/player-index])
        current-player-index (rf/subscribe [::subs/current-player-index])
        stage (rf/subscribe [::subs/stage])
        game-state (rf/subscribe [::subs/game-state])]
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
  (let [game-state (rf/subscribe [::subs/game-state])
        player-index (rf/subscribe [::subs/player-index])
        stage (rf/subscribe [::subs/stage])]
    (when (= @stage :public-vote)
      (if (get-in @game-state [:players player-index :vote])
        [:h4.text-center "Your vote has been recorded"]
        [:div
         [:h4.text-center "Should these players go on the mission?"]
         [:div.text-center
          [:button.btn.btn-success
           {:style   {:border-radius :1.5rem}
            :onClick #(rf/dispatch [::events/cast-public-vote player-index :pass])}
           [:span.far.fa-thumbs-up.fa-2x]]
          [:button.btn.btn-danger
           {:style   {:border-radius :1.5rem}
            :onClick #(rf/dispatch [::events/cast-public-vote player-index :fail])}
           [:span.far.fa-thumbs-down.fa-2x]]]]))))

(defn render-private-vote []
  (let [game-state (rf/subscribe [::subs/game-state])
        player-index (rf/subscribe [::subs/player-index])
        stage (rf/subscribe [::subs/stage])]
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
            :onClick #(rf/dispatch [::events/cast-secret-vote player-index :pass])}
           [:span.far.fa-thumbs-up.fa-2x]]
          [:button.btn.btn-danger
           {:style   {:border-radius :1.5rem}
            :onClick #(rf/dispatch [::events/cast-secret-vote player-index :fail])}
           [:span.far.fa-thumbs-down.fa-2x]]]]))))

(defn render-assassin-instructions []
  (let [players (rf/subscribe [::subs/players])
        player-index (rf/subscribe [::subs/player-index])
        stage (rf/subscribe [::subs/stage])]
    (when (= @stage :assassin-guess)
      (if (= :assassin (get-in players [player-index :role]))
        [:h4.text-center "Select a player to assassinate"]
        [:h4.text-center "Waiting for the assassin"]))))

(defn render-active-player [player-index]
  (let [active-player (rf/subscribe [::subs/current-player-index])]
    (when (= @active-player player-index) [:span.fas.fa-crown.text-warning])))

(def player-button-style
  {;:font-family "Times New Roman"
   :border-radius :1.5rem
   :border-color  "#CCCCCC"})

(defn render-player-nominations []
  (let [players (rf/subscribe [::subs/players])
        nominees (rf/subscribe [::subs/nominees])
        active-player (rf/subscribe [::subs/current-player-index])
        stage (rf/subscribe [::subs/stage])]
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
             :onClick #(rf/dispatch [::events/nominate @active-player i])}
            [render-active-player i]
            player-name]))])))

(defn render-players-public-vote []
  (let [players (rf/subscribe [::subs/players])
        nominees (rf/subscribe [::subs/nominees])
        stage (rf/subscribe [::subs/stage])]
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
             {:onClick #(rf/dispatch [::events/cast-public-vote i :pass])}]
            [render-active-player i]
            player-name
            [:span.float-right.far.fa-thumbs-down.text-danger
             {:onClick #(rf/dispatch [::events/cast-public-vote i :fail])}]]))])))

(defn render-players-secret-vote []
  (let [players (rf/subscribe [::subs/players])
        nominees (rf/subscribe [::subs/nominees])
        stage (rf/subscribe [::subs/stage])]
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
               {:onClick #(rf/dispatch [::events/cast-secret-vote i :pass])}])
            [render-active-player i]
            player-name
            (when (@nominees i)
              [:span.float-right.far.fa-thumbs-down.text-danger
               {:onClick #(rf/dispatch [::events/cast-secret-vote i :fail])}])]))])))


(defn render-players-assassin []
  (let [players (rf/subscribe [::subs/players])
        nominees (rf/subscribe [::subs/nominees])
        assassin-index (rf/subscribe [::subs/assassin-index])
        stage (rf/subscribe [::subs/stage])]
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
             :onClick #(rf/dispatch [::events/assassinate @assassin-index i])}
            player-name]))])))

(defn render-players-endgame []
  (let [players (rf/subscribe [::subs/players])
        nominees (rf/subscribe [::subs/nominees])
        stage (rf/subscribe [::subs/stage])]
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
               {:onClick #(rf/dispatch [::events/cast-secret-vote i :pass])}])
            [render-active-player i]
            (str player-name ":" (name role))
            (when (@nominees i)
              [:span.float-right.far.fa-thumbs-down.text-danger
               {:onClick #(rf/dispatch [::events/cast-secret-vote i :fail])}])]))])))

(defn render-players []
  (let [stage (rf/subscribe [::subs/stage])]
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
     [:h2 @stage]]))

(defn render-role []
  (let [player-index (rf/subscribe [::subs/player-index])
        players (rf/subscribe [::subs/players])]
    (let [{:keys [role]} (get @players @player-index)]
      [:h1.text-center (some-> role name)])))

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
  (rf/dispatch [::events/initialize {:players players}])
  (dom/render [render] (.getElementById js/document "ui-root")))

(defn init []
  (rf/dispatch [::events/initialize {:players players}])
  (let [root (.getElementById js/document "ui-root")]
    (.log js/console root)
    (dom/render [render] root)))

(comment
  )

