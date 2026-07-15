(ns nursingcareops.store-contract-test
  "Contract tests for `nursingcareops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [nursingcareops.store :as store]))

(deftest mem-store-resident-lookup
  (testing "MemStore can store and retrieve residents by ID (string keys)"
    (let [residents {"r1" {:resident-id "r1" :name "Alice" :registered? true :verified? true}}
          s (store/mem-store residents)]
      (is (some? (store/resident s "r1")))
      (is (nil? (store/resident s "r99"))))))

(deftest mem-store-all-residents
  (testing "MemStore returns all residents in sorted order"
    (let [residents {"r2" {:resident-id "r2" :name "Bob"}
                     "r1" {:resident-id "r1" :name "Alice"}
                     "r3" {:resident-id "r3" :name "Carol"}}
          s (store/mem-store residents)
          all-r (store/all-residents s)]
      (is (= 3 (count all-r)))
      (is (= "r1" (:resident-id (first all-r))))
      (is (= "r3" (:resident-id (last all-r)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-resident-note :resident-id "r1" :value {:meal "lunch"}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-residents
  (testing "MemStore with-residents replaces the resident directory"
    (let [s (store/mem-store {})
          new-residents {"r1" {:resident-id "r1" :name "Alice"}}]
      (is (= 0 (count (store/all-residents s))))
      (store/with-residents s new-residents)
      (is (= 1 (count (store/all-residents s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo residents"
    (let [s (store/seed-db)]
      (is (> (count (store/all-residents s)) 0))
      (is (some? (store/resident s "resident-1")))
      (is (some? (store/resident s "resident-2")))
      (is (some? (store/resident s "resident-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for site-id"
    (let [demo (store/demo-data)
          residents (:residents demo)]
      (doseq [[k v] residents]
        (is (string? k) "keys must be strings")
        (is (string? (:resident-id v)) "resident-id must be string")
        (is (= k (:resident-id v)) "key must match resident-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
