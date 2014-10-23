(ns asyncerr.server
  (:gen-class)
  (:use compojure.core)
  (:use ring.util.response)
  (:require [org.httpkit.server :as hk]
            [cheshire.core :as json]
            [ring.middleware.params :as params]
            [compojure.route :as route]
            [compojure.handler :as handler]))

(defn wrap-json-response [handler]
  (fn [request]
    (let [response (handler request)]
      (if (map? (:body response))
        (-> response
            (content-type "application/json")
            (update-in [:body] json/encode))
        response))))

(def all-cities {"CA" ["Los Angeles" "San Diego" "San Jose" "San Francisco"]
                 "FL" ["Jacksonville" "Miami" "Tampa" "Orlando" "Hialeah"]})

(def all-populations {"Los Angeles" 3792621
                      "San Diego" 1301617
                      "San Jose" 945942
                      "San Francisco" 805235
                      "Jacksonville" 823316  
                      "Miami" 408568
                      "Tampa" 335709
                      "Orlando" 239037
                      "Hialeah" 225461})

(defroutes main-routes
  (GET "/" [] "Error handling, Kyiv Clojure Meetup, 22 Oct 2014")
  (GET "/state" []
       (fn [{:keys [params]}]
         (let [code (get params "code")]
           (if (= code "NV")
             {:status 500 :body "What the hellll???"}
             (let [cities (all-cities code)]
               (if cities
                 {:status 200 :body {:cities cities}}
                 {:status 400 :body "Unknown state, sorry"}))))))
  (GET "/population" []
       (fn [{:keys [params]}]
         (let [name (get params "city")]
           (if (= name "Hollywood")
             {:status 500 :body "Bro, leave me alone..."}
             (let [p (all-populations name)]
               (if p
                 {:status 200 :body {:population p}}
                 {:status 400 :body "Unknown city, sorry"}))))))
  (route/not-found "404 Nothing to see here, move along"))

(def app
  (-> #'main-routes
      params/wrap-params
      wrap-json-response))

(defn -main [& args]
  (println "Running server on :5031")
  (hk/run-server app {:port 5031}))
