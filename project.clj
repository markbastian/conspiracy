(defproject conspiracy "0.0.1-SNAPSHOT"
  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [org.clojure/clojurescript "1.10.597"]
   [thheller/shadow-cljs "2.15.10"]
   [reagent "1.1.0"]
   [com.andrewmcveigh/cljs-time "0.5.2"]
   [haslett "0.1.6"]
   [cljs-ajax "0.8.4"]
   [hiccup-bridge "1.0.1"]
   [markbastian/partsbin "0.1.2"]
   [re-frame "1.2.0"]
   ;
   [org.immutant/web "2.1.10"]
   [com.taoensso/timbre "5.1.2"]
   [aleph "0.4.6"]
   [metosin/reitit "0.5.15"]
   [metosin/ring-http-response "0.9.3"]
   [ring/ring-defaults "0.3.3"]
   [com.fasterxml.jackson.core/jackson-core "2.13.0"]
   ]

  :plugins [[hiccup-bridge "1.0.1"]
            [lein-cljsbuild "1.1.7"]]

  :source-paths ["src/main/clj" "src/main/cljc"]
  :test-paths ["src/test/clj" "src/test/cljc"]
  :resource-paths ["src/main/resources"]

  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src/main/cljs" "src/main/cljc"]
                        :compiler {:main conspiracy.app
                                   :asset-path "js/compiled/out"
                                   :output-to "resources/public/js/compiled/conspiracy.js"
                                   :output-dir "resources/public/js/compiled/out"
                                   :source-map-timestamp true}}
                       ;; This next build is an compressed minified build for
                       ;; production. You can build this with:
                       ;; lein cljsbuild once min
                       {:id "min"
                        :source-paths ["src/main/cljs" "src/main/cljc"]
                        :compiler {:output-to "resources/public/js/compiled/conspiracy.js"
                                   :main conspiracy.app
                                   :optimizations :advanced
                                   :pretty-print false}}]}
  )
