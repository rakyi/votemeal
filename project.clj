(defproject votemeal "0.0.1-SNAPSHOT"
  :description "Vote for places to get your next meal"
  :url "https://github.com/rakyi/votemeal"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[buddy/buddy-core "1.5.0"]
                 [ch.qos.logback/logback-classic "1.2.3" :exclusions [org.slf4j/slf4j-api]]
                 [environ "1.1.0"]
                 [io.pedestal/pedestal.jetty "0.5.4"]
                 [io.pedestal/pedestal.service "0.5.4"]
                 [org.clojure/clojure "1.9.0"]
                 [org.clojure/core.async "0.4.474"]
                 [org.clojure/math.combinatorics "0.1.6"]
                 [org.julienxx/clj-slack "0.5.6"]
                 [org.slf4j/jcl-over-slf4j "1.7.25"]
                 [org.slf4j/jul-to-slf4j "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]]
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  ;; If you use HTTP/2 or ALPN, use the java-agent to pull in the correct alpn-boot dependency
  ;:java-agents [[org.mortbay.jetty.alpn/jetty-alpn-agent "2.0.5"]]
  :uberjar-name "votemeal.jar"
  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "votemeal.server/run-dev"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.5.4"]]}
             :uberjar {:aot [votemeal.server]}}
  :main ^{:skip-aot true} votemeal.server)

