(ns asyncerr.core
  (:require [clojure.core.async :as async]
            [cheshire.core :as json]
            [org.httpkit.client :as http]))

;; 1. We have server that can answer:
;; - state -> list of cities
;; - city -> population

(defn req [path options]
  (let [resp (async/chan)]
    (async/go
     (http/get (str "http://localhost:5031" path)
               (assoc options :as :text)
               (fn [{:keys [body]}]
                 (async/put! resp (json/parse-string body true))
                 (async/close! resp))))
    resp))

;; type State = String
;; cities :: State -> Chan[List[City]]
(defn cities [state]
  (->> {:query-params {"code" state}}
       (req "/state")
       (async/map< :cities)))

;; note, please use transducers instead of async/(map*|filter*|mapcat*)

;; num-cities :: State -> Chan[Int]
(defn num-cities [state]
  (async/map< count (cities state)))

;; city-populaion :: City -> Chan[Population]
(defn city-population [city]
  (->> {:query-params {"city" city}}
       (req "/population")
       (async/map< :population)))

;; this one is tricky...
;; very carefully about chan close operations (!)
(defn state-population [state]
  (let [cities-chan (cities state)]
    (async/go
     (let [cs (async/<! cities-chan)
           populations-chan (async/merge (map city-population cs))]
       (async/reduce + 0 populations-chan)))))

;; average-population :: State -> Chan[Float]
;; average-population = state-population / num-cities
(defn average-population [state]
  (let [population-chan (state-population state)
        cities-num-chan (num-cities state)]
    (async/go (/ (async/<! population-chan)
                 (async/<! cities-num-chan)))))

;; and another on:
;; city-area :: City -> Area
;; state-area = map city-area . cities
;; state-density = state-population / state-area

;; note, how async operations propagates through the code
;; ideally, you shouldn't have any "sync" points
;; and req -> resp should be transducer
;; btw, the same goes for errors

;; how to handle error if resp code = 400?
;; usually we use "nil" in Clojure
;; but that's not a case because async/chan limitations

;; 3. Separated error channel

(defn req-dual [path options]
  (let [resp (async/chan)
        err (async/chan)]
    (async/go
     (http/get (str "http://localhost:5031" path)
               (assoc options :as :text)
               (fn [{:keys [body status]}]
                 (if (= 200 status)
                   (async/put! resp (json/parse-string body true))
                   (async/put! err {:status status :body body}))
                 (async/close! resp)
                 (async/close! err))))
    [resp err]))

;; keep values and error separated
;; simple API for such cases:

(defn cities-dual [state]
  (let [resp (async/chan)
        err (async/chan)
        [cities-chan cities-err] (req-dual "/state"
                                           {:query-params {"code" state}})]
    (async/go
     (async/alt!
      cities-chan ([v] (async/>! resp (:cities v)))
      cities-err ([e] (async/>! err e)))
     (async/close! resp)
     (async/close! err))
    [resp err]))

;; but it's very hard to do something like state-population
;; looks like Scala futures with .onSuccess & .onFailure

;; 4. Maybe a = Just a | Nothing

;; duality: yes | no
;; doesn't fit well, because we have
;; - domain errors (no such city)
;; - non-domain errors (latencies, network partitions, dns lookup)
;; - incidental errors (the scheme of your channels is broken)

;; Either a b (concept of left and right values)
;; represent with

;; a) two-value vector
[nil ["Los Angeles" "San Diego" "San Jose" "San Francisco"]]
[{:status 400, :body "Unknown state, sorry"} nil]

;; b) two-value with specificator
[:left {:status 400, :body "Unknown state, sorry"}]
[:right []]

;; c) record
(defrecord Either [left right])

;; what we will need:
;; left? / right?
;; left-value / right-value
;; constructors for left/right

(defn left [err] (Either. err nil))
(defn right [value] (Either. nil value))

(right 0)

(defn right? [either] (nil? (:left either)))
(def left? (comp not right?))

(def right-value :right)
(def left-value :left)

;; questions: value = nil; Either[Either[T]] =? Either[T]
;; dynamic language :)

(defn req-either [path options]
  (let [resp (async/chan)]
    (async/go
     (http/get (str "http://localhost:5031" path)
               (assoc options :as :text)
               (fn [{:keys [body status]}]
                 (async/put! resp (if (= 200 status)
                                    (right (json/parse-string body true))
                                    (left {:status status :body body})))
                 (async/close! resp))))
    resp))

;; cities :: State -> Chan[Either[Error,List[City]]]
(defn cities-either [state]
  (->> {:query-params {"code" state}}
       (req-either "/state")
       (async/map< (fn [value]
                     (if (left? value)
                       value
                       (right (:cities (right-value value))))))))

;; num-cities :: State -> Chan[Either[Error,Int]]
(defn num-cities-either [state]
  (async/map< (fn [value]
                (if (left? value)
                  value
                  (right (count (right-value value)))))
              (cities-either state)))

;; city-populaion :: City -> Chan[Population]
(defn city-population-either [city]
  (->> {:query-params {"city" city}}
       (req-either "/population")
       (async/map< (fn [value]
                     (if (left? value)
                       value
                       (right (:population (right-value value))))))))

;; this one is tricky...
;; very carefully about chan close operations (!)
(defn state-population-either [state]
  (let [resp (async/chan)
        cities-chan (cities-either state)]
    (async/go
     (let [cs (async/<! cities-chan)]
       (if (left? cs)
         (do
           (async/>! resp cs)
           (async/close! resp))
         (let [right-cs (right-value cs)
               chans (map city-population-either right-cs)
               populations-chan (async/merge chans)
               ;; note, that there are few others "merge" strategies
               total (async/reduce (fn [acc value]
                                     (if (left? acc)
                                       acc
                                       (if (left? value)
                                         value
                                         (right (+ (right-value acc)
                                                   (right-value value))))))
                                   (right 0)
                                   populations-chan)]
           (async/pipe total resp)))))
    resp))

;; ... it really hard to manage such stuff without types checker
;; how to get read of all of this (if left?) (if right?)
;; ...
;; ...
;; Functor! (monad to be more accurate, but clojure community doesn't
;; like this word at all)

(defmulti fmap (fn [f value] (type value)))

(defmethod fmap Either [f v]
  (if (left? v) v (right (f (right-value v)))))

;; int -> string
;; int -> Either[string, string]

(fmap inc (right 10)) ;; => (right 11)
(fmap inc (left "Timeout")) ;; => (left "Timeout")

;; cities :: State -> Chan[Either[Error,List[City]]]
(defn cities-functor [state]
  (->> {:query-params {"code" state}}
       (req-either "/state")
       (async/map< (partial fmap :cities))))

;; num-cities :: State -> Chan[Either[Error,Int]]
(defn num-cities-functor [state]
  (async/map< (partial fmap count) (cities-either state)))

;; what else?
;; sequence :: List[Either[Error,T]] -> Either[List[Error],List[T]]
(defn sequence-either [values]
  (if (every? right? values)
    (right (map right-value values))
    (left (map left-value (filter left? values)))))

;; fold
(defn reduce-either [f init-acc values]
  (reduce (fn [acc value]
            (if (left? acc)
              acc
              (if (left? value)
                value
                (right (f (right-value acc)
                          (right-value value))))))
          (right init-acc) values))

;; but this is still painful:
(defn average-population-either [state]
  (let [population-chan (state-population-either state)
        cities-num-chan (num-cities-either state)
        resp (async/chan)]
    ;; ????
    resp))

;; functor for: A -> B
;; monad for: A -> Either[B]
;; using monadic "bind" we can build Scala for-yield analog 
(comment
  (either/for-yield*
   population <- (state-population-either state)
   num <- (num-cities-either state)
   (/ population num)))

;; note,
;; (fmap (comp f1 f2 f3)) >> (comp (fmap f1) (fmap f2) (fmap f3))

;; 5. Timeouts

;; easy for http client requests

;; for other operations:
(defn cities-timeout [state wait]
  (let [resp (async/chan)
        resp-chan (req-either "/state" {:query-params {"code" state}})
        timer (async/timeout wait)]
    (go
     (async/alt!
      resp-chan ([v] (async/<! resp (fmap :cities v)))
      timer ([_] (async/<! resp (left "Timeout"))))
     (async/close! resp))
    resp))

;; (with-timeout wait ...) macro helps a lot
;; but it's hard to plan in advance

;; 6. "Derivatives"
;; - debug wrong channels network
;; - debug buffers & overflows
;; - exceptions propagation
;; - back pressure
;; - cancel propagations
;; - ...
;; - ... welcome to the world of distributed computations!

;; about exceptions
(comment
  (try
    (/ 1 0)
    (catch Exception e (.getMessage e)))
  
  ;; you're definitely out of luck
  (try
    (async/go
     (async/<! (async/timeout 1000))
     (/ 1 0))
    (catch Exception e (.getMessage e)))

  ;; not that easy to see
  (defn took-this-from-library []
    (async/go
     (async/<! (async/timeout 1000))
     (/ 1 0)))

  (try (took-this-from-library) (catch Exception e e)))

;; yeah, exceptions are evil

;;
;; David McNeil
;; "A core.async Debugging Toolkit"
;; StrangeLoop, 2014
;; => http://goo.gl/geJqRq
;;
