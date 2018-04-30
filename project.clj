(defproject dutch-auction "0.0.1-SNAPSHOT"
  :plugins [[lein-cljsbuild "1.1.5"]]
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.10.238"]
                 [com.cognitect/transit-cljs "0.8.243"]
                 [funcool/promesa "1.9.0"]
                 [re-frame "0.10.5"]
                 [reagent "0.8.0"]]
  :repl-options {:repl-verbose     true
                 :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
  ;:jvm-opts ["--add-modules" "java.xml.bind"]
  :source-paths ["src"]
  :cljsbuild {:builds [{:id           "server"
                        :source-paths ["src"]
                        :compiler     {:main           dutch-auction.core
                                       :output-dir     "target/server"
                                       :install-deps   true
                                       :npm-deps       {"body-parser" "^1.18.2",
                                                        "express" "^4.16.3",
                                                        "knex" "^0.14.6",
                                                        "source-map-support" "^0.5.5",
                                                        "sqlite" "^2.9.1",
                                                        "sqlite3" "^4.0.0",
                                                        "sse-express" "next"
                                                        "minimist" "1.2.0"}
                                       :optimizations  :none
                                       :target         :nodejs
                                       :parallel-build true
                                       }}
                       {:id           "client"
                        :source-paths ["src"]
                        :compiler     {:main           dutch-auction.client
                                       :output-to      "resources/index.js"
                                       :optimizations  :simple
                                       :install-deps   true
                                       :parallel-build true
                                       ;:checked-arrays :warn
                                       }}]}
  :profiles {:dev {:dependencies [[org.clojure/tools.nrepl "0.2.13"]
                                  [orchestra "2017.11.12-1"]
                                  [org.bodil/cljs-nashorn "0.1.2"]
                                  [com.cemerick/piggieback "0.2.2"]]}})

