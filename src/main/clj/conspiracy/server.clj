(ns conspiracy.server
  (:require [partsbin.core :refer [create start stop restart system reset-config!]]
            [partsbin.immutant.web.core :as web]
            [integrant.core :as ig]
            [immutant.web.async :as async]
            [taoensso.timbre :as timbre]
            [muuntaja.middleware :as middleware]
            [reitit.ring :as ring]
            [reitit.coercion.spec]
            [reitit.core :as r]
            [ring.middleware.params :as params]
            [ring.util.http-response :refer [ok not-found resource-response bad-request]]
            [clojure.pprint :as pp]))

(defonce channels (atom {}))

(defn connect! [channel user]
  (timbre/info "channel open")
  (swap! channels assoc channel user))

(defn disconnect! [channel {:keys [code reason]}]
  (when-some [user (@channels channel)]
    (let [fmt-str "Closing channel for user %s with code %s. Reason: %s."]
      (timbre/info (format fmt-str user code reason))
      (swap! channels dissoc channel))))

;(notify-clients! nil "{\"action\":\"run\"}")
;(notify-clients! nil "{\"abc\":123}")
(defn notify-clients! [source-channel msg]
  (println msg)
  (doseq [[channel] @channels]
    (async/send! channel msg)))

(defn ws-handler [{:keys [params websocket?] :as request}]
  (if websocket?
    (if-some [user (params "user")]
      (do
        (timbre/info (format "Connecting user %s." user))
        (async/as-channel request {:on-open    #(connect! % user)
                                   :on-close   #'disconnect!
                                   :on-message #'notify-clients!}))
      (bad-request "No user provided in websocket request."))
    (bad-request "Not a websocket request.")))

(def websocket-routes
  [["/ws" ws-handler]])

(def router
  (ring/router
    websocket-routes
    {:data {:coercion   reitit.coercion.spec/coercion
            :middleware [params/wrap-params
                         middleware/wrap-format]}}))

(def handler
  (ring/ring-handler
    router
    (constantly (not-found "Not found"))))

(def config {::web/server {:host    "0.0.0.0"
                           :port    3000
                           ;:sql-conn (ig/ref ::jdbc/connection)
                           ;:dsdb     (ig/ref ::datascript/connection)
                           :handler #'handler}})

(defonce sys (create config))
