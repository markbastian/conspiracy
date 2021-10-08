(ns conspiracy.api
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [ajax.core :refer [GET POST]]
            [cljs.pprint :as pp]
            [reagent.core :as r]
            [cljs.core.async :as a :refer [<! >! put!]]
            [haslett.client :as ws]
            [haslett.format :as hfmt]
            [clojure.walk :refer [keywordize-keys]]))

(defmulti handle-ws-response (fn [state response] (keyword (get response "action"))))

;(defmethod handle-ws-response :individual_update [state {:strs [body] :as response}]
;  (let [{:keys [userid] :as m} (keywordize-keys body)]
;    (swap! state assoc-in [:individuals userid] m)))

(defmethod handle-ws-response :message [state {:strs [time sender message] :as response}]
  (prn "I got a message")
  (prn response)
  (swap! state update :messages
         (fn [v] (vec (conj v {:time time
                               :sender sender
                               :message message})))))

(defmethod handle-ws-response :default [state {:strs [body] :as response}]
  (println response)
  #_(println (keyword (get response "action"))))

(defn handle-ws-traffic [state]
  (go-loop [message (<! (get-in @state [:stream :source]))]
           (when message
             (do
               (handle-ws-response state message)
               (recur (<! (get-in @state [:stream :source])))))))

(def ws-url "ws://localhost:3000/ws?user=")

(defn send-message [state message]
  (if-some [sink (get-in @state [:stream :sink])]
    (go (>! sink {:action  :message
                  :time    (js/Date)
                  :sender  "Mark"
                  :message message}))
    (prn "No websocket connection")))

(defn connect-ws [state user]
  (go-loop [stream (<! (ws/connect (str ws-url user) {:format hfmt/json}))]
           (swap! state assoc :stream stream)
           (handle-ws-traffic state)
           (let [close-status (<! (get-in @state [:stream :close-status]))]
             (prn (str "Socket closing due to: " close-status ".")))))

(defn infinite-connect-ws [state]
  (go-loop [stream (<! (ws/connect ws-url {:format hfmt/json}))]
           (swap! state assoc :stream stream)
           (handle-ws-traffic state)
           (let [close-status (<! (get-in @state [:stream :close-status]))]
             (prn (str "Socket closing due to: " close-status ". Reconnecting..."))
             (recur (<! (ws/connect ws-url {:format hfmt/json}))))))