(ns conspiracy.api
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [ajax.core :refer [GET POST]]
            [cljs.pprint :as pp]
            [reagent.core :as r]
            [cljs.core.async :as a :refer [<! >! put!]]
            [haslett.client :as ws]
            [haslett.format :as hfmt]
            [clojure.walk :refer [keywordize-keys]]))

;;TODO add APIs for backend communication