(ns jepsen.crate.dirty-read
  "Searches for dirty reads."
  (:refer-clojure :exclude [test])
  (:require [jepsen [core         :as jepsen]
                    [db           :as db]
                    [checker      :as checker]
                    [client       :as client]
                    [generator    :as gen]
                    [independent  :as independent]
                    [nemesis      :as nemesis]
                    [net          :as net]
                    [tests        :as tests]
                    [util         :as util :refer [meh
                                                   timeout
                                                   with-retry]]
                    [os           :as os]]
            [jepsen.os.debian     :as debian]
            [jepsen.checker.timeline :as timeline]
            [jepsen.crate         :as c]
            [clojure.string       :as str]
            [clojure.set          :as set]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :refer [info warn]]
            [knossos.op           :as op])
  (:import (io.crate.client CrateClient)
           (io.crate.action.sql SQLActionException
                                SQLResponse
                                SQLRequest)
           (io.crate.shade.org.elasticsearch.client.transport
             NoNodeAvailableException)))

(defn client
  ([] (client nil (atom 0)))
  ([conn limit]
   (let [initialized? (promise)]
    (reify client/Client
      (setup! [this test node]
        (let [conn (c/await-client (c/connect node) test)]
          (when (deliver initialized? true)
            (c/sql! conn "create table dirty_read (
                           id integer primary key
                         ) with (number_of_replicas = \"0-all\")"))
          (client conn limit)))

      (invoke! [this test op]
        (timeout (case (:f op)
                   :refresh 60000
                   500)
                 (assoc op :type :info, :error :timeout)
                 (c/with-errors op
                   (case (:f op)
                     ; Read a specific ID
                     :read (let [v (->> (c/sql! conn "select id from dirty_read
                                                     where id = ?"
                                                (:value op))
                                        :rows
                                        first
                                        :id)]
                             (assoc op :type (if v :ok :fail)))

                     ; Refresh table
                     :refresh (do (c/sql! conn "refresh table dirty_read")
                                  (assoc op :type :ok))

                     ; Perform a full read of all IDs
                     :strong-read
                     (do (->> (c/sql! conn
                                      "select id from dirty_read LIMIT ?"
                                      (+ 100 @limit)) ; who knows
                              :rows
                              (map :id)
                              (into (sorted-set))
                              (assoc op :type :ok, :value)))

                     ; Add an ID
                     :write (do (swap! limit inc)
                                (c/sql! conn
                                        "insert into dirty_read (id) values (?)"
                                        (:value op))
                                (assoc op :type :ok))))))

      (teardown! [this test]
        (.close conn))))))

(defn es-client
  "Elasticsearch based client. Wraps an underlying Crate client for some ops."
  ([] (es-client (client) nil))
  ([crate es]
   (reify client/Client
     (setup! [this test node]
       (let [crate (client/setup! crate test node)
             es    (c/es-connect node)]
         (es-client crate es)))

     (invoke! [this test op]
       (timeout 10000 (assoc op :type :info, :error :timeout)
                (case (:f op)
                  :strong-read
                  (->> (c/search-table es)
                       (map #(get (:source %) "id"))
                       (into (sorted-set))
                       (assoc op :type :ok, :value))

                  (client/invoke! crate test op))))

     (teardown! [this test]
       (client/teardown! crate test)
       (.close es)))))

(defn checker
  "Verifies that we never read an element from a transaction which did not
  commmit (and hence was not visible in a final strong read).

  Also verifies that every successful write is present in the strong read set."
  []
  (reify checker/Checker
    (check [checker test model history opts]
      (let [ok    (filter op/ok? history)
            writes (->> ok
                        (filter #(= :write (:f %)))
                        (map :value)
                        (into (sorted-set)))
            reads (->> ok
                       (filter #(= :read (:f %)))
                       (map :value)
                       (into (sorted-set)))
            strong-read-sets (->> ok
                                  (filter #(= :strong-read (:f %)))
                                  (map :value))
            on-all       (reduce set/intersection strong-read-sets)
            on-some      (reduce set/union strong-read-sets)
            not-on-all   (set/difference on-some  on-all)
            unseen       (set/difference on-some  reads)
            dirty        (set/difference reads    on-some)
            lost         (set/difference writes   on-some)
            some-lost    (set/difference writes   on-all)
            nodes-agree? (= on-all on-some)]
        ; We expect one strong read per node
        (info :strong-read-sets (count strong-read-sets))
        (info :concurrency (:concurrency test))

        ; Everyone should have read something
        (assert (= (count strong-read-sets) (:concurrency test)))

        {:valid?                         (and nodes-agree?
                                              (empty? dirty)
                                              (empty? lost))
         :nodes-agree?                   nodes-agree?
         :read-count                     (count reads)
         :on-all-count                   (count on-all)
         :on-some-count                  (count on-some)
         :unseen-count                   (count unseen)
         :not-on-all-count               (count not-on-all)
         :not-on-all                     not-on-all
         :dirty-count                    (count dirty)
         :dirty                          dirty
         :lost-count                     (count lost)
         :lost                           lost
         :some-lost-count                (count some-lost)
         :some-lost                      some-lost}))))

(defn sr  [_ _] {:type :invoke, :f :strong-read, :value nil})

(defn rw-gen
  "While one process writes to a node, we want another process to see that the
  in-flight write is visible, in the instant before the node crashes."
  []
  (let [; What did we write last?
        write (atom -1)
        ; A vector of in-flight writes on each node.
        in-flight (atom nil)]
    (reify gen/Generator
      (op [_ test process]
        ; lazy init of in-flight state
        (when-not @in-flight
          (compare-and-set! in-flight
                            nil
                            (vec (repeat (count (:nodes test)) 0))))

        (let [; thread index
              t (gen/process->thread test process)
              ; node index
              n (mod process (count (:nodes test)))]
          (if (= t n)
            ; The first n processes perform writes
            (let [v (swap! write inc)]
              ; Record the in-progress write
              (swap! in-flight assoc n v)
              {:type :invoke, :f :write, :value v})

            ; Remaining processes try to read the most recent write
            {:type :invoke, :f :read, :value (nth @in-flight n)}))))))

(defn test
  [opts]
  (merge tests/noop-test
         {:name    "crate lost-updates"
          :os      debian/os
          :db      (c/db)
          :client  (client)
          :checker (checker/compose
                     {:dirty-read (checker)
                      :perf       (checker/perf)})
          :concurrency 15
          :nemesis (nemesis/partition-random-halves)
          :generator (gen/phases
                       (->> (rw-gen)
                            (gen/stagger 1/100)
                            (gen/nemesis ;nil)
                              (gen/seq (cycle [(gen/sleep 40)
                                               {:type :info, :f :start}
                                               (gen/sleep 120)
                                               {:type :info, :f :stop}])))
                            (gen/time-limit 200))
                       (gen/nemesis (gen/once {:type :info :f :stop}))
                       (gen/clients (gen/each
                                      (gen/once {:type :invoke, :f :refresh})))
                       (gen/log "Waiting for quiescence")
                       (gen/sleep 10)
                       (gen/clients (gen/each (gen/once sr))))}
         opts))
