(ns nursingcareops.governor-test
  "Pure unit tests of `nursingcareops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [nursingcareops.governor :as gov]
            [nursingcareops.store :as store]))

(def resident-1 {:resident-id "resident-1" :name "Alice Chen" :registered? true :verified? true})
(def resident-3 {:resident-id "resident-3" :name "Carol Martinez" :registered? true :verified? false})

(defn- clean-proposal [op resident-id]
  {:op op :resident-id resident-id :summary "s" :rationale "routine care coordination"
   :cites [resident-id] :effect :propose :value {} :confidence 0.85})

(deftest resident-unregistered-is-hard
  (testing "no resident record at all -> HARD hold"
    (let [s (store/mem-store {"resident-1" resident-1})
          verdict (gov/check {} nil (clean-proposal :log-resident-note "unknown-resident") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:resident-unverified} (map :rule (:violations verdict)))))))

(deftest resident-unverified-is-hard
  (testing "resident registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"resident-3" resident-3})
          verdict (gov/check {} nil (clean-proposal :log-resident-note "resident-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:resident-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"resident-1" resident-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-family-or-guardian-visit "resident-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed five-op allowlist is a scope violation"
    (let [s (store/mem-store {"resident-1" resident-1})
          verdict (gov/check {} nil (clean-proposal :adjust-care-plan "resident-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest medication-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches medication/dosing scope is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"resident-1" resident-1})
          poisoned (assoc (clean-proposal :log-resident-note "resident-1")
                          :rationale "adjusted the resident's medication regimen and dosing schedule"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest clinical-diagnosis-content-is-hard
  (testing "a proposal touching clinical-diagnosis/assessment is HARD-blocked, same as medication"
    (let [s (store/mem-store {"resident-1" resident-1})
          poisoned (assoc (clean-proposal :log-resident-note "resident-1")
                          :rationale "revised clinical assessment and diagnosed new condition"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest care-plan-change-content-is-hard
  (testing "a proposal touching care-plan/care-coordination changes is HARD-blocked"
    (let [s (store/mem-store {"resident-1" resident-1})
          poisoned (assoc (clean-proposal :schedule-family-or-guardian-visit "resident-1")
                          :summary "modify care plan and adjust therapeutic approach")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest restraint-content-is-hard
  (testing "a proposal touching physical-restraint use is HARD-blocked"
    (let [s (store/mem-store {"resident-1" resident-1})
          poisoned (assoc (clean-proposal :schedule-staff-shift-proposal "resident-1")
                          :value {:decision "authorize physical restraint use for safety"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest end-of-life-content-is-hard
  (testing "a proposal touching end-of-life or DNR decisions is HARD-blocked"
    (let [s (store/mem-store {"resident-1" resident-1})
          poisoned (assoc (clean-proposal :coordinate-supply-request "resident-1")
                          :summary "recommend do not resuscitate order and end-of-life planning")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-safety-concern-is-not-scope-excluded
  (testing "flagging observed falls/wellbeing concerns as a SAFETY CONCERN (not a clinical diagnosis) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"resident-1" resident-1})
          concern (assoc (clean-proposal :flag-safety-concern "resident-1")
                         :value {:concern "resident reported dizziness and loss of balance near bathroom"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (falls/wellbeing) is exactly what this op exists to surface"))))
