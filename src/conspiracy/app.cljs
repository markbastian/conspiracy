;(shadow.cljs.devtools.api/nrepl-select :app)
(ns conspiracy.app
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as r]
            [cljs-time.core :as t]
            [cljs-time.format :as fmt]
            [ajax.core :refer [GET POST]]
            [clojure.string :as cs]
            [conspiracy.rules :as rules]
            [clojure.walk :refer [keywordize-keys]]
            [cljs.core.async :as a :refer [<! >!]]
            [clojure.pprint :as pp]))

(def players
  [{:name "Mark"}
   {:name "Becky"}
   {:name "Chloe"}
   {:name "Gregor"}
   {:name "Luxa"}
   {:name "Boots"}])

(defonce state (r/atom {:player-index 0
                        :game-state   (rules/init-game players)}))
(def warning (r/cursor state [:warning]))

(defn log [m] (.log js/console m))

(defn render-results-track [state]
  (let [results (r/cursor state [:game-state :results])]
    (fn [state]
      [:span.text-center
       (doall
         (for [[i result :as k] (map vector (range) @results)]
           ^{:key k}
           (if (= result :pass)
             [:span.far.fa-thumbs-up.fa-2x.text-success 1]
             [:span.far.fa-thumbs-down.fa-2x.text-danger 1])))])))

(defn render-players [state]
  (let [players (r/cursor state [:game-state :players])
        game-state (r/cursor state [:game-state])
        active-player (r/cursor state [:game-state :current-player-index])
        nominees (r/cursor state [:game-state :nominees])
        player-index (get-in @state [:player-index])
        this-player (@players player-index)]
    (fn [state]
      (let [stage (rules/stage @game-state)]
        [:div
         (doall
           (for [[{player-name :name :keys [role vote] :as player} i] (map vector @players (range))]
             ^{:key player}
             [:button.btn.btn-lg.btn-block
              (cond->
                {:style (cond-> {                           ;:font-family "Times New Roman"
                                 :border-radius :1.5rem
                                 :border-color  "#CCCCCC"}
                                (@nominees i)
                                (assoc :background-color "#E0E0E0"))}
                (= :nomination stage)
                (assoc :onClick (fn [_]
                                  (swap! game-state rules/nominate @active-player i)
                                  #_(swap! game-state rules/nominate player-index i))))
              (when (= @active-player i)
                [:span.fas.fa-crown.text-warning])
              (when (= stage :good-wins) (and (rules/good-roles role))
                [:span.float-left.fas.fa-medal.text-success])
              (when (= stage :public-vote)
                [:span.float-left.far.fa-thumbs-up.text-success
                 {:onClick #(swap! game-state rules/cast-public-vote i :pass)}])
              (when (and (= stage :private-vote) (@nominees i))
                [:span.float-left.far.fa-thumbs-up.text-success
                 {:onClick #(swap! game-state rules/cast-secret-vote i :pass)}])
              player-name
              (when (= stage :public-vote)
                [:span.float-right.far.fa-thumbs-down.text-danger
                 {:onClick #(swap! game-state rules/cast-public-vote i :fail)}])
              (when (and (= stage :private-vote) (@nominees i))
                [:span.float-right.far.fa-thumbs-down.text-danger
                 {:onClick #(swap! game-state rules/cast-secret-vote i :fail)}])]))
         [:br]
         [:div.text-center
          [:button.btn.btn-lg
           {:style   {:border-radius    :1.5rem
                      :background-color "#66FF00"}
            :onClick (fn [_]
                       (print "click")
                       (swap! game-state rules/cast-public-vote player-index :pass))}
           [:span.far.fa-thumbs-up.fa-2x]]
          [:button.btn.btn-lg
           {:style   {:border-radius    :1.5rem
                      :background-color "#FF0000"}
            :onClick (fn [_]
                       (print "click")
                       (swap! game-state rules/cast-public-vote player-index :fail))}
           [:span.far.fa-thumbs-down.fa-2x]]]
         [render-results-track state]
         [:p (with-out-str (pp/pprint @game-state))]
         [:p stage]]))))

(defn render-role [state]
  (let [player-index (get-in @state [:player-index])
        {:keys [role]} (get-in @state [:game-state :players player-index])]
    [:h1.text-center (name role)]))

(defn render [state]
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
  (r/render [render state] (.getElementById js/document "ui-root")))

(defn init []
  (let [root (.getElementById js/document "ui-root")]
    (.log js/console root)
    (r/render [render state] root)))

(comment
  )

