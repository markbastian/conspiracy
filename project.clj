(defproject conspiracy "0.0.1-SNAPSHOT"
  :dependencies
  [[org.clojure/clojure "1.10.0"]
   [org.clojure/clojurescript "1.10.520"]
   [thheller/shadow-cljs "2.8.37"]
   [reagent "0.8.1"]
   [com.andrewmcveigh/cljs-time "0.5.2"]
   [haslett "0.1.6"]
   [cljs-ajax "0.8.0"]
   [hiccup-bridge "1.0.1"]
   [markbastian/partsbin "0.1.2"]]

  :plugins [[hiccup-bridge "1.0.1"]]

  :source-paths
  ["src"])
