(ns nursingcareops.advisor
  "SkilledNursingAdvisor -- the *contained intelligence node* for the
  ISIC-871 residential nursing care facilities operations-coordination actor.

  It drafts exactly five kinds of back-office proposal from a closed
  allowlist: resident-note logging, family/guardian visit scheduling,
  non-medication supply coordination, staff shift proposals, and
  safety-concern flagging. CRITICAL: it is a smart-but-untrusted advisor.
  It returns a *proposal* (with a rationale + the fields it cited), never
  a committed record and NEVER a direct actuation -- every proposal's
  `:effect` is always `:propose`. Every output is censored downstream by
  `nursingcareops.governor` before anything touches the SSoT.

  This advisor NEVER drafts medication/pharmaceutical decisions, nursing
  assessment, clinical diagnosis, care-plan changes, wound care,
  IV/catheter management, vital signs monitoring, physical restraint use,
  end-of-life decisions, or safety-authority actions -- those are
  permanently out of scope for this actor (and extra-conservatively
  scanned given skilled nursing's clinical adjacency), not merely
  un-implemented. `nursingcareops.governor`'s `scope-exclusion-violations`
  independently re-scans every proposal for exactly this failure mode
  (a compromised or confused advisor drifting into scope it must never
  touch) and HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :resident-id str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-resident-note
  "Draft a resident-note log entry. Pure logging of observed routine care
  (meals, mood, activity, hygiene) -- NEVER a nursing assessment or
  clinical judgment."
  [_db {:keys [resident-id patch]}]
  {:op         :log-resident-note
   :resident-id resident-id
   :summary    (str resident-id " の日常的入居者ノートを記録: " (pr-str (keys patch)))
   :rationale  "入居者の日常的な活動・食事・気分・衛生の観察記録のみ。臨床判断なし。看護判断なし。"
   :cites      [resident-id]
   :effect     :propose
   :value      (merge {:resident-id resident-id} patch)
   :confidence 0.94})

(defn- propose-family-or-guardian-visit
  "Draft a family/guardian visit scheduling proposal (a calendar
  entry, never a direct dispatch or clinical oversight)."
  [_db {:keys [resident-id patch]}]
  {:op         :schedule-family-or-guardian-visit
   :resident-id resident-id
   :summary    (str resident-id " の家族・保護者面会予定を提案: " (pr-str (keys patch)))
   :rationale  "入居者と家族・保護者の面会時間調整のみ。面会実施の決定は家族・保護者と入居者が行う。"
   :cites      [resident-id]
   :effect     :propose
   :value      (merge {:resident-id resident-id} patch)
   :confidence 0.89})

(defn- propose-supply-request
  "Draft a NON-MEDICATION consumable supply request coordination
  (linens, mobility aids, food stock, incontinence supplies, grooming items --
  ABSOLUTELY NEVER medication, pharmaceuticals, medical devices, or
  clinical supplies like dressings, catheters, IVs)."
  [_db {:keys [resident-id patch]}]
  {:op         :coordinate-supply-request
   :resident-id resident-id
   :summary    (str resident-id " に関連する非医薬品消耗品リクエスト: " (pr-str (keys patch)))
   :rationale  "布製品・移動補助具・食料品・失禁用品などの非医薬品消耗品の調達調整のみ。投薬・医療用品なし。"
   :cites      [resident-id]
   :effect     :propose
   :value      (merge {:resident-id resident-id} patch)
   :confidence 0.91})

(defn- propose-staff-shift
  "Draft a staff-shift roster PROPOSAL only (never a binding assignment).
  Actual shift finalization is always done by shift supervisors."
  [_db {:keys [resident-id patch]}]
  {:op         :schedule-staff-shift-proposal
   :resident-id resident-id
   :summary    (str resident-id " のケア担当者シフト提案: " (pr-str (keys patch)))
   :rationale  "ケアスタッフのシフト割り当て提案のみ。確定は人間の シフト管理者が判断する。"
   :cites      [resident-id]
   :effect     :propose
   :value      (merge {:resident-id resident-id} patch)
   :confidence 0.87})

(defn- propose-safety-concern
  "Surface a resident/facility safety concern (falls, wellbeing incidents,
  observed distress) for HUMAN triage. This op ALWAYS escalates in
  `nursingcareops.governor` -- never auto-committed at any phase -- regardless
  of how confident the advisor is that the concern is real."
  [_db {:keys [resident-id patch]}]
  {:op         :flag-safety-concern
   :resident-id resident-id
   :summary    (str resident-id " の安全懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "入居者の身体的・心理的安全に関する観察事実の報告。常に人間の確認・対応が必要。"
   :cites      [resident-id]
   :effect     :propose
   :value      (merge {:resident-id resident-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-resident-note (propose-resident-note _db request)
                   :schedule-family-or-guardian-visit (propose-family-or-guardian-visit _db request)
                   :coordinate-supply-request (propose-supply-request _db request)
                   :schedule-staff-shift-proposal (propose-staff-shift _db request)
                   :flag-safety-concern (propose-safety-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use. This injects NURSING-SPECIFIC scope violations.
    (if out-of-scope?
      (update proposal :rationale str " -- medication IV infusion adjustment, nursing assessment of wound dressing, care plan modification for catheter removal")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :resident-id (:resident-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
