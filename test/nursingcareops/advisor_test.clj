(ns nursingcareops.advisor-test
  "Unit tests of `nursingcareops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [nursingcareops.advisor :as adv]
            [nursingcareops.store :as store]))

(def db (store/seed-db))

(deftest propose-care-note-shape
  (testing "care-note proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-resident-note
                           :resident-id "resident-1"
                           :patch {:meal "lunch" :mood "cheerful"}})]
      (is (= :log-resident-note (:op p)))
      (is (= "resident-1" (:resident-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :resident-id)))))

(deftest propose-family-visit-shape
  (testing "family-visit proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-family-or-guardian-visit
                           :resident-id "resident-2"
                           :patch {:visitor "son" :date "2026-07-20"}})]
      (is (= :schedule-family-or-guardian-visit (:op p)))
      (is (= "resident-2" (:resident-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-supply-request-shape
  (testing "supply-request proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-supply-request
                           :resident-id "resident-1"
                           :patch {:item "linens" :quantity 2}})]
      (is (= :coordinate-supply-request (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-staff-shift-shape
  (testing "staff-shift proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-staff-shift-proposal
                           :resident-id "resident-1"
                           :patch {:caregiver "Chen" :shift "morning"}})]
      (is (= :schedule-staff-shift-proposal (:op p)))
      (is (= :propose (:effect p)))
      (is (>= (:confidence p) 0.85)))))

(deftest propose-safety-concern-shape
  (testing "safety-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-safety-concern
                           :resident-id "resident-1"
                           :patch {:concern "fall risk observed"}})]
      (is (= :flag-safety-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-resident-note :schedule-family-or-guardian-visit :coordinate-supply-request
                :schedule-staff-shift-proposal :flag-safety-concern]]
      (let [p (adv/infer db {:op op :resident-id "resident-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-resident-note :schedule-family-or-guardian-visit :coordinate-supply-request
                :schedule-staff-shift-proposal :flag-safety-concern]]
      (let [p (adv/infer db {:op op :resident-id "resident-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
