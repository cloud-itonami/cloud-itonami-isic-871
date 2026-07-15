(ns nursingcareops.governor
  "SkilledNursingGovernor -- the independent compliance layer for
  ISIC-871 residential nursing care facilities. The advisor has no notion
  of whether a resident is actually registered and verified, whether
  its own proposed `:effect` secretly claims a direct actuation instead
  of a mere proposal, or whether it has silently drifted into a
  permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- COORDINATION ONLY
  (resident-note logging, family/guardian visit scheduling, non-medication
  supply coordination, staff shift proposals, safety-concern flagging).
  It NEVER performs or authorizes:
    - medication administration, dosing, prescribing, or any pharma handling
    - nursing assessment or clinical diagnosis
    - care-plan changes or modifications
    - wound care, IV management, catheter care, or any clinical procedures
    - vital signs monitoring or patient assessment
    - physical restraint, seclusion, or mobility restrictions
    - end-of-life or DNR decisions
    - safety-authority overrides (complaint investigation, license
      enforcement, compliance actions)

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Resident unverified      -- the target resident record must
                                   exist AND be independently
                                   confirmed :registered?/:verified?
                                   in the store before ANY proposal
                                   for it may commit or even escalate.
                                   Never trusts a proposal's own claim
                                   about the resident -- re-derived from
                                   the resident's own store record, the
                                   same 'ground truth, not self-report'
                                   discipline every sibling actor's
                                   governor uses.
    2. Effect not :propose      -- every proposal's :effect MUST
                                   be :propose. Any other effect value
                                   is, by construction, a claim to
                                   directly actuate/commit outside
                                   governance -- HARD block, not merely
                                   low-confidence.
    3. Scope exclusion          -- ANY proposal (regardless of op)
                                   whose op, rationale, summary,
                                   citations or draft value touches
                                   medication/pharmaceutical/nursing-
                                   assessment/clinical-diagnosis/care-
                                   plan/wound-care/IV/catheter/vital-
                                   signs/restraint/end-of-life/safety-
                                   authority territory is a HARD,
                                   PERMANENT block. Skilled nursing is
                                   clinically adjacent, so EXTRA-
                                   CONSERVATIVE scope exclusions apply.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is :flag-safety-concern -- ALWAYS escalates to a human, regardless
  of confidence, regardless of how clean the proposal otherwise is.
  `nursingcareops.phase` independently agrees: :flag-safety-concern is
  never a member of any phase's :auto set either -- two layers, not
  one."
  (:require [clojure.string :as str]
            [nursingcareops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist for skilled nursing COORDINATION ONLY.
  An op outside this set is a scope violation by construction."
  #{:log-resident-note :schedule-family-or-guardian-visit :coordinate-supply-request
    :schedule-staff-shift-proposal :flag-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area. Skilled nursing is clinically
  adjacent, so scope exclusions are EXTRA-CONSERVATIVE. Covers medication,
  pharmaceutical, nursing assessment, clinical decision-making, wound care,
  IV/catheter management, vital signs, physical restraint, end-of-life
  decisions, or safety-authority enforcement. Scanned across the proposal's
  op/summary/rationale/cites/value, never trusting the advisor's own intent."
  ;; Medication & pharmaceutical (primary clinical exclusion for skilled nursing)
  ["medicatio" "薬" "dosing" "処方" "prescription" "rx" "pharma" "drug"
   "iv fluid" "infusion" "inject" "intravenous" "subcutaneous"
   ;; Nursing assessment & clinical diagnosis
   "nursing assessment" "nursing-assessment" "clinical assessment" "clinical diagnosis"
   "clinical-diagnosis" "臨床診断" "assessment" "vital sign" "vital-sign" "vitals" "diagnos"
   "blood pressure" "bp reading" "heart rate" "respiration" "temperature" "temp"
   "blood glucose" "glucose monitoring" "o2 saturation" "oxygen sat"
   ;; Care plan & treatment decisions
   "care plan" "care-plan" "ケアプラン" "treatment plan" "treatment-plan"
   "care coordination change" "medical treatment" "therapy plan"
   ;; Wound & procedure care
   "wound care" "wound-care" "創傷" "dressing" "suture" "stitch"
   "catheter" "カテーテル" "foley" "urinary catheter" "central line"
   "feeding tube" "peg tube" "tracheostomy" "ostomy"
   ;; Physical restrictions & mobility
   "physical restraint" "physical-restraint" "身体拘束" "restraint" "拘束"
   "seclusion" "隔離" "mobility restriction" "fall prevention device"
   ;; End-of-life & safety authority
   "end of life" "end-of-life" "dnr" "do not resuscitate" "終末期" "advance directive"
   "code status" "palliative" "hospice"
   "safety authority" "safety-authority" "safety enforcement" "license suspension"
   "license-suspension" "compliance enforcement" "compliance-enforcement"
   "investigat" "complaint" "違反"])

;; ----------------------------- checks -----------------------------

(defn- resident-unverified-violations
  "The target resident must exist AND be independently `:registered?`/
  `:verified?` in the store -- never trust the proposal's own
  `:resident-id` claim without a store lookup."
  [{:keys [resident-id]} st]
  (let [r (store/resident st resident-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :resident-unverified
        :detail (str resident-id " は未登録または未検証の入居者 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches medication/clinical/restraint/end-of-life/
  safety-authority territory, regardless of confidence or how clean
  every other check is. Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "投薬/臨床判断/ケアプラン変更/身体拘束/終末期判断/安全当局の判断領域に触れる提案は永久に禁止"}])))

(defn check
  "Censors an ElderCareAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [resident-id (or (:resident-id proposal) (:resident-id request))
        hard (into []
                   (concat (resident-unverified-violations {:resident-id resident-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :resident-id (:resident-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
