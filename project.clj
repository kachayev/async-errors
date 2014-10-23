(defproject asyncerr "0.1.0-SNAPSHOT"
  :description "Error handling, Kyiv Clojure Meetup, 22 Oct 2014"
  :url "http://kachayev.github.io/talks/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main asyncerr.server
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [http-kit "2.1.16"]
                 [ring "1.3.1"]
                 [compojure "1.2.0"]
                 [cheshire "5.3.1"]])
