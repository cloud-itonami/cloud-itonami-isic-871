(ns nursingcareops.phase
  "Phase 0->3 staged rollout for the ISIC-871 skilled nursing care
  facilities operations-coordination actor.

    Phase 0  read-only            -- no writes, still governor-gated.
    Phase 1  assisted-logging     -- resident-note logging allowed,
                                     every write needs human approval.
    Phase 2  assisted-coordination-- adds family/guardian visit, supply request,
                                     staff shift proposals, still approval.
    Phase 3  supervised auto      -- governor-clean, high-confidence
                                     `:log-resident-note`/
                                     `:schedule-family-or-guardian-visit`/
                                     `:coordinate-supply-request`/
                                     `:schedule-staff-shift-proposal` may
                                     auto-commit. `:flag-safety-concern`
                                     NEVER auto-commits, at any phase.

  `:flag-safety-concern` is deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. Flagging a safety concern always
  needs a human to actually look at it. `nursingcareops.governor`'s own
  `always-escalate-ops` enforces the same invariant independently --
  two layers, not one, agree on this."
  (:require [nursingcareops.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: `:flag-safety-concern` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member
;; of any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops
  allowed to auto-commit when governor-clean>}."
  {0 {:label "read-only"              :writes #{}                                                                           :auto #{}}
   1 {:label "assisted-logging"       :writes #{:log-resident-note}                                                             :auto #{}}
   2 {:label "assisted-coordination"  :writes #{:log-resident-note :schedule-family-or-guardian-visit :coordinate-supply-request
                                               :schedule-staff-shift-proposal}                                             :auto #{}}
   3 {:label "supervised-auto"        :writes write-ops
      :auto #{:log-resident-note :schedule-family-or-guardian-visit :coordinate-supply-request :schedule-staff-shift-proposal}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE
    (:phase-approval), even if the governor was clean.
  - `:flag-safety-concern` is never auto-eligible at any phase, so it
    always escalates once the governor clears it (or holds if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map an ElderCareGovernor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
