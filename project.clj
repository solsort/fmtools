(defproject solsort.fmtools/fmtools "0.0.1-SNAPSHOT"

  :dependencies
  [[org.clojure/clojure "1.8.0"]
   [org.clojure/clojurescript "1.7.170"]
   [org.clojure/core.async "0.2.374"]
   [cljsjs/pouchdb "5.2.1-0"]
   [solsort/util "0.1.2"]
   [com.cognitect/transit-cljs "0.8.237"]
   [reagent "0.5.1"]
   [re-frame "0.6.0"]]

  :plugins
  [[lein-cljsbuild "1.1.1"]
   [lein-ancient "0.6.8"]
   [lein-figwheel "0.5.0-2"]
   [lein-bikeshed "0.2.0"]
   [lein-kibit "0.1.2"]]

  :source-paths ["src/" "test/"]

  :clean-targets ^{:protect false}
  ["resources/public/out"
   "resources/public/index.js"
   "resources/public/tests.js"
   "resources/public/out-tests"
   "figwheel_server.log"
   "out/"
   "target/"]

  :cljsbuild
  {:builds
   [{:id "dev"
     :source-paths ["src/"]
     :figwheel
     {:websocket-host ~(.getHostAddress (java.net.InetAddress/getLocalHost))
      ; :on-jsload ""
      }
     :compiler
     {:main solsort.fmtools.main
      :asset-path "out"
      :output-to "resources/public/index.js"
      :output-dir "resources/public/out"
      :source-map-timestamp true }}
    {:id "dist"
     :source-paths ["src"]
     :compiler
     {:output-to "index.js"
      :main solsort.fmtools.main
      :externs ["externs.js"]
      :optimizations :advanced
      :pretty-print false}}]}
  :figwheel
  {:nrepl-port ~(read-string (or (System/getenv "FIGWHEEL_NREPL_PORT") "7888"))
   :server-port ~(read-string (or (System/getenv "FIGWHEEL_SERVER_PORT") "3449"))})