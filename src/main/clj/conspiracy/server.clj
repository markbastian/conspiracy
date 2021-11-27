(ns conspiracy.server
  (:require [partsbin.core :refer [create start stop restart system reset-config!]]
            [partsbin.immutant.web.core :as web]
            [hiccup.page :refer [html5 include-js include-css]]
            [integrant.core :as ig]
            [immutant.web.async :as async]
            [taoensso.timbre :as timbre]
            [muuntaja.middleware :as middleware]
            [reitit.ring :as ring]
            [reitit.coercion.spec]
            [reitit.core :as r]
            [ring.middleware.params :as params]
            [ring.middleware.resource :as resource]
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

(def web-page
  (html5
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:title "Conspiracy!"]
      [:link {:rel "stylesheet" :href "https://maxcdn.bootstrapcdn.com/bootstrap/4.2.1/css/bootstrap.min.css" :crossorigin "anonymous"}]
      [:link {:rel "stylesheet" :href "https://use.fontawesome.com/releases/v5.8.2/css/all.css" :integrity "sha384-oS3vJWv+0UjzBfQzYUhtDYW+Pj2yciDJxpsK1OYPAYjqT085Qq/1cq5FLXAZQ7Ay" :crossorigin "anonymous"}]
      [:script {:src "https://code.jquery.com/jquery-3.2.1.slim.min.js" :integrity "sha384-KJ3o2DKtIkvYIK3UENzmM7KCkRr/rE9/Qpg6aAZGJwFDMVNA/GpGFF93hXpG5KkN" :crossorigin "anonymous"}]
      [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/popper.js/1.12.9/umd/popper.min.js" :integrity "sha384-ApNbgh9B+Y1QKtv3Rn7W3mgPxhU9K/ScQsAP7hUibX39j7fakFPskvXusvfa0b4Q" :crossorigin "anonymous"}]
      [:script {:src "https://maxcdn.bootstrapcdn.com/bootstrap/4.2.1/js/bootstrap.min.js" :crossorigin "anonymous"}]]
     [:body
      [:div#ui-root]
      [:script {:src "main.js"}]]]))

(def websocket-routes
  [["/ws" ws-handler]
   ["/conspiracy" {:get (constantly (ok web-page))}]])

(def router
  (ring/router
    websocket-routes
    {:data {:coercion   reitit.coercion.spec/coercion
            :middleware [params/wrap-params
                         middleware/wrap-format]}}))

(def handler
  (ring/ring-handler
    router
    (ring/routes
      (ring/create-resource-handler)
      (constantly (not-found "Not found")))))

(def config {::web/server {:host    "0.0.0.0"
                           :port    3000
                           ;:sql-conn (ig/ref ::jdbc/connection)
                           ;:dsdb     (ig/ref ::datascript/connection)
                           :handler #'handler}})

(defonce sys (create config))
