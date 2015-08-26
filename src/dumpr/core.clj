(ns dumpr.core
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.core.async :as async :refer [chan >!!]]
            [taoensso.timbre :as log]
            [dumpr.query :as query]
            [dumpr.events :as events]
            [dumpr.stream :as stream]
            [dumpr.binlog :as binlog]))

(def ^:dynamic *parallel-table-loads* 2)
(def load-buffer-default-size 1000)
(def stream-buffer-default-size 50)

;; Abstract Data Type for streamed rows
;;
(defn upsert [table id content]
  [:upsert table id content])

(defn delete [table id]
  [:delete table id])

(defn upsert? [data]
  (= (first data) :upsert))

(defn delete? [data]
  (= (first data) :delete))



(defn- ensure-table-spec [name-or-spec]
  (if (keyword? name-or-spec)
    {:table name-or-spec
     :id-fn :id}
    name-or-spec))



;; Public API
;;

(defn create [conf tables]
  {:db-spec (query/db-spec conf) :conf conf :tables tables})

(defn load-tables
  ([ctx] (load-tables ctx (chan load-buffer-default-size)))
  ([{:keys [db-spec tables]} out]
   (let [binlog-pos (query/binlog-position db-spec)
         in         (chan 0)
         _          (async/pipeline-async *parallel-table-loads*
                                          out
                                          (partial query/stream-table db-spec) ; TODO upserts
                                          in)
         _          (async/onto-chan in (map ensure-table-spec tables))]
     {:out out
      :binlog-pos binlog-pos})))

(defn binlog-stream
  ([ctx binlog-pos] (binlog-stream ctx binlog-pos (chan stream-buffer-default-size)))
  ([ctx binlog-pos out]
   (let [db-spec      (:db-spec ctx)
         events-xform (comp (map events/parse-event)
                            (remove nil?)
                            stream/filter-txs
                            (stream/add-binlog-filename (:filename binlog-pos))
                            stream/group-table-maps)
         events-ch    (chan 1 events-xform)
         client       (binlog/new-binlog-client (:conf ctx)
                                         binlog-pos
                                         events-ch)]
     (async/pipeline-blocking 2
                              out
                              (comp (map #(stream/fetch-table-schema db-spec %))
                                    (map stream/convert-with-schema)
                                    cat)
                              events-ch)
     {:client client
      :out out})))


(defn start-binlog-stream [stream-ctx]
  (binlog/start-client (:client stream-ctx)))

(defn close-binlog-stream [stream-ctx]
  (binlog/stop-client (:client stream-ctx)))
