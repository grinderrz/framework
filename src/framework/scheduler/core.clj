(ns framework.scheduler.core
  (:require
    [clojure.core.async :as async :refer [<! chan timeout close! go-loop]]
    [clojure.core.async.impl.protocols :refer [closed?]]
    [clojure.tools.logging :as logging])
  (:import
    (java.lang
      AutoCloseable)))

(defrecord Closeable-channels-atom
  [channels]
  AutoCloseable
  (close [this]
    (doseq [c @(:channels this)]
      (swap! (:channels this) disj c)
      (close! c))))

(defonce channels
  (atom #{}))

(defn start
  [deps action interval-msecs]
  (let [chan (chan)]
    (go-loop [chan chan]
      (async/<! (timeout interval-msecs))
      (logging/debugf "Executing scheduled action %s" action)
      (if (or
            (nil? chan)
            (closed? chan))
        (logging/debugf "Stop %s execution" action)
        (do
          (action deps)
          (recur chan))))
    (swap! channels conj chan)
    (assoc deps :scheduled-jobs (->Closeable-channels-atom channels))))