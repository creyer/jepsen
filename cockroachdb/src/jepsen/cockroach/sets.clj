(ns jepsen.cockroach.sets
  "Set test"
  (:refer-clojure :exclude [test])
  (:require [jepsen [cockroach :as c]
             [client :as client]
             [checker :as checker]
             [generator :as gen]
             [independent :as independent]
             [util :as util :refer [meh]]]
            [jepsen.cockroach.nemesis :as cln]
            [clojure.java.jdbc :as j]
            [clojure.core.reducers :as r]
            [clojure.set :as set]
            [clojure.tools.logging :refer :all]
            [knossos.model :as model]
            [knossos.op :as op]))

(defn check-sets
  "Given a set of :add operations followed by a final :read, verifies that
  every successfully added element is present in the read, and that the read
  contains only elements for which an add was attempted, and that all
  elements are unique."
  []
  (reify checker/Checker
    (check [this test model history opts]
      (let [attempts (->> history
                          (r/filter op/invoke?)
                          (r/filter #(= :add (:f %)))
                          (r/map :value)
                          (into #{}))
            adds (->> history
                      (r/filter op/ok?)
                      (r/filter #(= :add (:f %)))
                      (r/map :value)
                      (into #{}))
            fails (->> history
                       (r/filter op/fail?)
                       (r/filter #(= :add (:f %)))
                       (r/map :value)
                       (into #{}))
            unsure (->> history
                        (r/filter op/info?)
                        (r/filter #(= :add (:f %)))
                        (r/map :value)
                        (into #{}))
            final-read-l (->> history
                              (r/filter op/ok?)
                              (r/filter #(= :read (:f %)))
                              (r/map :value)
                              (reduce (fn [_ x] x) nil))]
        (if-not final-read-l
          {:valid? false
           :error  "Set was never read"}

          (let [final-read  (set final-read-l)

                dups        (into [] (for [[id freq] (frequencies final-read-l)
                                           :when (> freq 1)]
                                       id))

                ;;The OK set is every read value which we added successfully
                ok          (set/intersection final-read adds)

                ;; Unexpected records are those we *never* attempted.
                unexpected  (set/difference final-read attempts)

                ;; Revived records are those that were reported as failed and
                ;; still appear.
                revived  (set/intersection final-read fails)

                ;; Lost records are those we definitely added but weren't read
                lost        (set/difference adds final-read)

                ;; Recovered records are those where we didn't know if the add
                ;; succeeded or not, but we found them in the final set.
                recovered   (set/intersection final-read unsure)]

            {:valid?          (and (empty? lost)
                                   (empty? unexpected)
                                   (empty? dups)
                                   (empty? revived))
             :duplicates      dups
             :ok              (util/integer-interval-set-str ok)
             :lost            (util/integer-interval-set-str lost)
             :unexpected  (util/integer-interval-set-str unexpected)
             :recovered (util/integer-interval-set-str recovered)
             :revived (util/integer-interval-set-str revived)
             :ok-frac      (util/fraction (count ok) (count attempts))
             :revived-frac   (util/fraction (count revived) (count fails))
             :unexpected-frac (util/fraction (count unexpected) (count attempts))
             :lost-frac       (util/fraction (count lost) (count attempts))
             :recovered-frac  (util/fraction (count recovered) (count attempts))}))))))

(defrecord SetsClient [tbl-created? conn]
  client/Client

  (setup! [this test node]
    (let [conn (c/init-conn node)]
      (info node "Connected")

      (locking tbl-created?
        (when (compare-and-set! tbl-created? false true)
          (Thread/sleep 1000)
          (c/with-txn-notimeout {} [c conn]
            (j/execute! c ["drop table if exists set"]))
          (Thread/sleep 1000)
          (info node "Creating table")
          (c/with-txn-notimeout {} [c conn]
            (j/execute! c ["create table set (val int)"]))))

      (assoc this :conn conn)))

  (invoke! [this test op]
    (case (:f op)
      :add (c/with-txn op [c conn]
             (do
               (j/insert! c :set {:val (:value op)})
               (assoc op :type :ok)))
      :read (c/with-txn-notimeout op [c conn]
              (->> (j/query c ["select val from set"])
                   (mapv :val)
                   (assoc op :type :ok, :value)))))

  (teardown! [this test]
    (meh (c/with-timeout conn nil
           (j/execute! @conn ["drop table set"])))
    (c/close-conn @conn)))

(defn test
  [opts]
  (c/basic-test
    (merge
      {:name        "set"
       :concurrency c/concurrency-factor
       :client      {:client (SetsClient. (atom false) nil)
                     :during (->> (range)
                                  (map (partial array-map
                                                :type :invoke
                                                :f :add
                                                :value))
                                  gen/seq
                                  (gen/stagger 1))
                     :final (gen/once {:type :invoke, :f :read, :value nil})}
       :checker     (checker/compose
                      {:perf     (checker/perf)
                       :details  (check-sets)})}
      opts)))
