(ns nursingcareops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for `cloud-itonami-isic-871`: this repo previously had NO demo console
  and no generator at all.

  Everything on the generated page comes from a REAL run of this repo's
  own actor stack -- `nursingcareops.operation` (langgraph-clj
  StateGraph) -> `nursingcareops.governor` -> `nursingcareops.store` --
  driven through `langgraph.graph/run*` exactly as
  `nursingcareops.sim` drives it, including the real
  `interrupt-before #{:request-approval}` human-approval pause and
  resume.

  There is no hand-written HTML table content anywhere in this
  namespace. Specifically:

    - the resident directory is `store/all-residents` off the seeded
      MemStore (`store/demo-data`),
    - the rollout phase table is walked out of `phase/phases`, not
      re-typed,
    - the governor configuration table is read off
      `governor/allowed-ops`, `governor/always-escalate-ops`,
      `governor/confidence-floor` and `governor/scope-excluded-terms`,
    - every disposition, confidence, HARD-hold rule name and violation
      detail string is the governor's own output taken off the store
      ledger / the run state -- never a literal in this file,
    - the scope-exclusion evidence column re-runs the governor's OWN
      private `text-blob` fn over the real proposal, so the matched
      substrings are measured, not asserted,
    - the committed-coordination table is `store/coordination-log`,
    - the approver-retention disclosure is DERIVED at render time by
      diffing the record the graph handed to `store/commit-record!`
      (off the run state) against the record the store actually
      returned. It is not a hardcoded claim about this repo, so it
      stays true if the store changes.

  Where a value genuinely does not exist in the real run, the page says
  so rather than inventing one.

  Deterministic: no clock, no randomness, no network, no map-order
  dependence (payload maps are rendered key-sorted). Re-running writes a
  byte-identical file.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`).

  `-main` THROWS if the run produced no HARD governor hold -- a console
  that shows no real hold is not evidence of a governor. Precedent:
  cloud-itonami-isic-2513."
  (:require [kotoba.lang.text :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [nursingcareops.advisor :as advisor]
            [nursingcareops.governor :as governor]
            [nursingcareops.operation :as op]
            [nursingcareops.phase :as phase]
            [nursingcareops.store :as store]))

;; ----------------------------- the real run -----------------------------

(defn- ctx
  "An operator context at a given rollout phase. `:actor-id` is the
  human/system principal the graph records on every audit fact."
  [phase-n]
  {:actor-id "coord-1" :actor-role :care-coordinator :phase phase-n})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- resume! [actor tid approval]
  (g/run* actor {:approval approval} {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives a freshly seeded store through a scenario that reaches every
  disposition this actor can produce, and returns
  `{:db .. :runs [..]}`.

  Each entry of `:runs` is one logical request:
  `{:id :label :phase :request :initial :approval :final}` where
  `:initial`/`:final` are whole `langgraph.graph/run*` results (the
  `:final` of an approval flow is the resume result).

  Coverage, in order:

    01 phase-1 resident note      -- governor-clean but not auto-eligible
                                     at phase 1 -> ESCALATE -> approved
                                     -> committed.
    02 phase-3 resident note      -- clean + high confidence -> AUTO-COMMIT.
    03 phase-3 family visit       -- clean -> AUTO-COMMIT.
    04 phase-3 staff shift        -- clean, different resident -> AUTO-COMMIT.
    05 phase-3 safety concern     -- ALWAYS escalates at every phase
                                     (governor `always-escalate-ops` AND
                                     absent from every phase `:auto` set)
                                     -> approved -> committed.
    06 phase-3 safety concern     -- same op, human REJECTS -> approval
                                     rejected, nothing written to the SSoT.
    07 phase-0 resident note      -- governor-clean, but phase 0 is
                                     read-only -> phase-gate HOLD
                                     (:phase-disabled). NOT a HARD hold.
    08 phase-3 supply request     -- HARD hold, :scope-excluded. Measured,
                                     not staged: the advisor's own
                                     negative disclaimer trips the
                                     extra-conservative substring scan.
    09 unregistered resident      -- HARD hold, :resident-unverified.
    10 registered-but-unverified  -- HARD hold, :resident-unverified.
    11 advisor claims :effect
       :commit                    -- HARD hold, :effect-not-propose.
    12 advisor drifts into
       medication/clinical scope  -- HARD hold, :scope-excluded.
    13 op outside the closed
       allowlist                  -- HARD hold, TWO rules at once
                                     (:effect-not-propose + :op-not-allowed).

  Nothing here invents a resident: `resident-1` `resident-2`
  `resident-3` are seeded by `store/demo-data`, and `resident-99` is
  deliberately absent from it (that absence is the point of run 09)."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        ;; A second actor identical to the first except that its advisor
        ;; claims a direct actuation. Same store, same governor.
        actor-direct (op/build db {:advisor (reify advisor/Advisor
                                              (-advise [_ _ req]
                                                (assoc (advisor/infer nil req) :effect :commit)))})
        runs (atom [])
        step! (fn [{:keys [id label phase request actor approval]
                    :or {actor actor}}]
                (let [initial (exec! actor id request (ctx phase))
                      final (if approval (resume! actor id approval) initial)]
                  (swap! runs conj {:id id :label label :phase phase
                                    :request request :initial initial
                                    :approval approval :final final})))]

    (step! {:id "r01" :phase 1 :label "resident note at phase 1 (assisted-logging)"
            :request {:op :log-resident-note :resident-id "resident-1"
                      :patch {:meal "lunch eaten" :mood "cheerful" :activity "art class"}}
            :approval {:status :approved :by "care-coordinator-1"}})

    (step! {:id "r02" :phase 3 :label "resident note at phase 3 (supervised-auto)"
            :request {:op :log-resident-note :resident-id "resident-1"
                      :patch {:meal "dinner eaten" :mood "calm" :activity "card game"}}})

    (step! {:id "r03" :phase 3 :label "family/guardian visit scheduling"
            :request {:op :schedule-family-or-guardian-visit :resident-id "resident-1"
                      :patch {:visitor-name "daughter Sarah" :date "2026-07-20" :time "14:00"}}})

    (step! {:id "r04" :phase 3 :label "care-staff shift proposal"
            :request {:op :schedule-staff-shift-proposal :resident-id "resident-2"
                      :patch {:caregiver "nurse tech Chen" :shift "morning" :date "2026-07-21"}}})

    (step! {:id "r05" :phase 3 :label "safety concern, human approves"
            :request {:op :flag-safety-concern :resident-id "resident-1"
                      :patch {:concern "resident reported loss of balance near bathroom"
                              :confidence 0.92}}
            :approval {:status :approved :by "facility-manager-2"}})

    (step! {:id "r06" :phase 3 :label "safety concern, human REJECTS"
            :request {:op :flag-safety-concern :resident-id "resident-2"
                      :patch {:concern "night-shift staffing gap reported by family"
                              :confidence 0.78}}
            :approval {:status :rejected :by "facility-manager-2"}})

    (step! {:id "r07" :phase 0 :label "resident note at phase 0 (read-only)"
            :request {:op :log-resident-note :resident-id "resident-1"
                      :patch {:meal "breakfast eaten" :mood "quiet"}}})

    (step! {:id "r08" :phase 3 :label "non-medication supply request"
            :request {:op :coordinate-supply-request :resident-id "resident-1"
                      :patch {:item "bed linens" :quantity 2 :urgency "routine"}}})

    (step! {:id "r09" :phase 3 :label "note for a resident absent from the directory"
            :request {:op :log-resident-note :resident-id "resident-99"
                      :patch {:meal "breakfast" :mood "unknown"}}})

    (step! {:id "r10" :phase 3 :label "note for a registered but unverified resident"
            :request {:op :log-resident-note :resident-id "resident-3"
                      :patch {:meal "breakfast" :mood "calm"}}})

    (step! {:id "r11" :phase 3 :label "advisor claims a direct actuation"
            :actor actor-direct
            :request {:op :schedule-family-or-guardian-visit :resident-id "resident-1"
                      :patch {:visitor-name "son" :date "2026-07-22"}}})

    (step! {:id "r12" :phase 3 :label "advisor drifts into clinical scope"
            :request {:op :log-resident-note :resident-id "resident-1"
                      :out-of-scope? true :patch {}}})

    (step! {:id "r13" :phase 3 :label "op outside the closed allowlist"
            :request {:op :administer-medication :resident-id "resident-1"
                      :patch {:dose "unspecified"}}})

    {:db db :runs @runs}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw->s [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- muted [s] (str "<span class=\"muted\">" s "</span>"))

(defn- ok [s] (str "<span class=\"ok\">" s "</span>"))

(defn- warn [s] (str "<span class=\"warn\">" s "</span>"))

(defn- critical [s] (str "<span class=\"critical\">" s "</span>"))

(defn- yes-no [b] (if b (ok "yes") (critical "no")))

(defn- sorted-map-str
  "Render a payload map deterministically: key-sorted `k=v` pairs. Never
  `pr-str` of the raw map -- map iteration order is not a contract."
  [m]
  (if (seq m)
    (->> m
         (sort-by (comp str key))
         (map (fn [[k v]] (str (kw->s k) "=" (pr-str v))))
         (str/join ", "))
    ""))

(defn- ops-str [ops]
  (if (seq ops)
    (str/join ", " (map #(code (str ":" (name %))) (sort (map name ops))))
    (muted "(none)")))

(defn- row [& cells]
  (str "        <tr>" (str/join "" (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join "" (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows) (str (str/join "\n" rows) "\n") "")
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (if lede (str "    <p class=\"muted\">" lede "</p>\n") "")
       body
       "  </section>\n"))

;; ----------------------------- derivations -----------------------------

(defn- final-state [run] (-> run :final :state))

(defn- hard-holds
  "HARD governor holds off the store ledger: a `:governor-hold` fact
  that carries at least one governor violation. A phase-gate hold is
  also written as `:governor-hold` but with an EMPTY `:violations`
  vector, so this filter deliberately separates compliance holds
  (permanent, un-overridable) from rollout holds (a phase away from
  being allowed)."
  [db]
  (filterv #(and (= :governor-hold (:t %)) (seq (:violations %))) (store/ledger db)))

(defn- phase-holds
  "Rollout-phase holds: `:governor-hold` facts with no governor
  violation, carrying a `:phase-reason`."
  [db]
  (filterv #(and (= :governor-hold (:t %)) (empty? (:violations %))) (store/ledger db)))

(defn- rejected-holds [db]
  (filterv #(= :approval-rejected (:t %)) (store/ledger db)))

(defn- committed-runs
  "Runs whose graph actually reached the `:commit` node, in commit
  order -- so they pair positionally with `store/coordination-log`."
  [runs]
  (filterv #(let [s (final-state %)]
              (and (= :commit (:disposition s)) (some? (:record s))))
           runs))

(defn- offered-record
  "The record the graph handed to `store/commit-record!` for this run
  (read off the run's own final state, before the store touched it)."
  [run]
  (:record (final-state run)))

(defn- approver-of-run
  "The approver as the RUN saw it: the `:approval-granted` audit fact
  the `:request-approval` node emitted. Note this fact never reaches
  the store ledger -- only the run's audit channel carries it."
  [run]
  (some->> (:audit (final-state run))
           (filter #(= :approval-granted (:t %)))
           first
           :by))

(defn- approver-in-stored
  "Where, if anywhere, the approver survived into the STORED record.
  Returns `[value key-path]` or nil. Checked against the record the
  store returned, not the one the graph offered."
  [stored]
  (cond
    (get-in stored [:payload :approved-by]) [(get-in stored [:payload :approved-by]) ":payload :approved-by"]
    (get-in stored [:value :approved-by]) [(get-in stored [:value :approved-by]) ":value :approved-by"]
    :else nil))

(defn- retention-report
  "MEASURED store behaviour. Diffs each record the graph offered
  against the record `store/coordination-log` actually returned, and
  counts how many approvals survived. Nothing here is asserted about
  this repo in advance -- if `store/commit-record!` starts dropping
  `:payload`, this report changes with it."
  [db runs]
  (let [stored (vec (store/coordination-log db))
        crs (committed-runs runs)
        pairs (mapv (fn [run rec]
                      (let [off (offered-record run)]
                        {:run run
                         :offered off
                         :stored rec
                         :dropped-keys (vec (sort (map kw->s (remove (set (keys rec)) (keys off)))))
                         :identical? (= off rec)
                         :run-approver (approver-of-run run)
                         :stored-approver (approver-in-stored rec)}))
                    crs stored)
        approved (filterv :run-approver pairs)]
    {:stored-count (count stored)
     :committed-run-count (count crs)
     :paired? (= (count stored) (count crs))
     :pairs pairs
     :approved-count (count approved)
     :retained-count (count (filter :stored-approver approved))
     :dropped-keys (vec (sort (distinct (mapcat :dropped-keys pairs))))
     :all-identical? (every? :identical? pairs)}))

(defn- scope-matches
  "Re-runs the governor's OWN private `text-blob` over a real proposal
  and reports which `scope-excluded-terms` actually matched. Uses the
  governor var rather than a copy of the logic, so this cannot drift
  from what the governor really did."
  [proposal]
  (let [blob (@#'governor/text-blob proposal)]
    (vec (filter #(str/includes? blob %) governor/scope-excluded-terms))))

(defn- disposition-cell [run]
  (let [s (final-state run)
        d (:disposition s)
        audit (:audit s)
        rejected? (some #(= :approval-rejected (:t %)) audit)
        phase-hold? (some #(and (= :governor-hold (:t %)) (:phase-reason %)) audit)]
    (cond
      (= :commit d) (if (approver-of-run run)
                      (ok "committed after human approval")
                      (ok "auto-committed"))
      rejected? (warn "approval rejected &middot; nothing written")
      phase-hold? (warn (str "phase-gate hold &middot; "
                             (esc (str ":" (name (some #(:phase-reason %) audit))))))
      (= :hold d) (critical "HARD governor hold")
      :else (muted (esc (str d))))))

(defn- rule-names [f]
  (str/join ", " (map #(code (str ":" (name (:rule %)))) (:violations f))))

;; ----------------------------- sections -----------------------------

(defn- residents-section [db]
  (section
   "Resident directory (seeded ground truth)"
   (str "Read from " (code "nursingcareops.store/all-residents") " on the store this run seeded from "
        (code "store/demo-data") ". The governor re-derives registration/verification from THESE fields on "
        "every single proposal &mdash; it never trusts a proposal's own claim about its resident. "
        "A resident id absent from this table (the run below drives " (code "resident-99") ") cannot be "
        "coordinated at all.")
   (table ["Resident id" "Name" "Registered" "Verified" "Coordination allowed"]
          (mapv (fn [{:keys [resident-id name registered? verified?]}]
                  (row (code resident-id) (esc name) (yes-no registered?) (yes-no verified?)
                       (if (and registered? verified?)
                         (ok "yes")
                         (critical "no &middot; HARD hold on every op"))))
                (store/all-residents db)))))

(defn- phase-section []
  (section
   "Rollout phase gate"
   (str "Walked out of " (code "nursingcareops.phase/phases") " &mdash; this table is generated from the "
        "same map the running graph gates on, so it cannot drift. The phase gate can only ADD caution: "
        "a governor HOLD stays a HOLD at every phase. Default phase is "
        (code (str phase/default-phase)) ".")
   (table ["Phase" "Label" "Ops allowed to write" "Ops allowed to auto-commit"]
          (mapv (fn [[n {:keys [label writes auto]}]]
                  (row (code (str n))
                       (esc label)
                       (ops-str writes)
                       (ops-str auto)))
                (sort-by key phase/phases)))))

(defn- governor-section []
  (section
   "Governor configuration"
   (str "Read off the public vars of " (code "nursingcareops.governor") ". The three HARD checks are "
        "permanent and un-overridable by any human approval; the escalation gate is soft (a human may "
        "clear it).")
   (table ["Setting" "Value" "Source var"]
          [(row "Closed op allowlist" (ops-str governor/allowed-ops) (code "governor/allowed-ops"))
           (row "Always escalates (never auto at any phase)"
                (ops-str governor/always-escalate-ops) (code "governor/always-escalate-ops"))
           (row "Confidence floor (below this &rarr; escalate)"
                (code (str governor/confidence-floor)) (code "governor/confidence-floor"))
           (row "Scope-exclusion substrings scanned"
                (str (code (str (count governor/scope-excluded-terms))) " terms, case-insensitive, over "
                     (code ":op :summary :rationale :cites :value"))
                (code "governor/scope-excluded-terms"))])))

(defn- timeline-section [runs]
  (section
   "Request timeline (this run)"
   (str "Every row is one real " (code "langgraph.graph/run*") " through "
        (code "intake &rarr; advise &rarr; govern &rarr; decide") ", plus the real "
        (code "interrupt-before #{:request-approval}") " pause and resume where a human was needed. "
        "Confidence is the advisor's own number off the run state.")
   (table ["#" "Scenario" "Phase" "Op" "Resident" "Advisor confidence" "Outcome" "Approver"]
          (mapv (fn [{:keys [id label phase request] :as run}]
                  (let [s (final-state run)
                        conf (:confidence (:verdict s))
                        appr (approver-of-run run)
                        approval (:approval run)]
                    (row (code id)
                         (esc label)
                         (code (str phase))
                         (code (str ":" (name (:op request))))
                         (code (:resident-id request))
                         (if (some? conf) (esc (str conf)) (muted "no proposal"))
                         (disposition-cell run)
                         (cond
                           appr (esc appr)
                           (= :rejected (:status approval)) (warn (str "rejected by " (esc (:by approval))))
                           :else (muted "not required")))))
                runs))))

(defn- hard-holds-section [db]
  (let [hs (hard-holds db)]
    (section
     "HARD governor holds (permanent, un-overridable)"
     (str "Straight off the append-only store ledger: facts with "
          (code ":t :governor-hold") " that carry at least one governor violation. "
          "The rule names and the Japanese detail strings below are the governor's own "
          (code ":violations") " entries &mdash; not text written into the renderer. "
          "None of these ever reached a human: a HARD hold has no approval path.")
     (table ["Op" "Resident" "Rule(s)" "Governor's own detail" "Advisor confidence at hold"]
            (mapv (fn [f]
                    (row (code (str ":" (name (:op f))))
                         (code (:resident-id f))
                         (rule-names f)
                         (str/join "<br>" (map #(esc (:detail %)) (:violations f)))
                         (esc (str (:confidence f)))))
                  hs)))))

(defn- soft-holds-section [db]
  (let [ph (phase-holds db)
        rj (rejected-holds db)]
    (section
     "Holds that are NOT compliance holds"
     (str "Both of these are also written to the ledger as holds and neither mutates the SSoT, but "
          "they are structurally different from a HARD hold: a phase-gate hold is a rollout state that "
          "a later phase removes, and a rejected approval is a human decision. Keeping them in a "
          "separate table is what makes the HARD-hold count above meaningful.")
     (table ["Kind" "Op" "Resident" "Reason" "Ledger fact type"]
            (into
             (mapv (fn [f]
                     (row (warn "rollout phase gate")
                          (code (str ":" (name (:op f))))
                          (code (:resident-id f))
                          (str (code (str ":" (name (:phase-reason f))))
                               " at phase " (code (str (:phase f))))
                          (code ":governor-hold")))
                   ph)
             (mapv (fn [f]
                     (row (warn "human rejected")
                          (code (str ":" (name (:op f))))
                          (code (:resident-id f))
                          (rule-names f)
                          (code ":approval-rejected")))
                   rj))))))

(defn- scope-evidence-section [runs]
  (let [scoped (filterv (fn [run]
                          (let [s (final-state run)]
                            (and (:proposal s)
                                 (some #(= :scope-excluded (:rule %))
                                       (:violations (:verdict s))))))
                        runs)]
    (section
     "Scope-exclusion scanner evidence (measured, not asserted)"
     (str "For each proposal the governor scope-excluded, this re-runs the governor's own "
          (code "text-blob") " fn over the real proposal and reports which of its "
          (code (str (count governor/scope-excluded-terms)))
          " substrings actually matched. "
          "This is why " (code "r08") " holds: nothing was staged for it &mdash; the advisor's own "
          "NEGATIVE disclaimer (&quot;&#25237;&#34220;&#12539;&#21307;&#30274;&#29992;&#21697;&#12394;&#12375;&quot;, "
          "i.e. &quot;no medication, no medical supplies&quot;) contains &#34220;, which is one of the scanned "
          "substrings. The scanner is deliberately extra-conservative for skilled nursing and does not "
          "read negation, so a benign bed-linen request is blocked. That is real measured behaviour of "
          "this actor today, shown rather than hidden.")
     (table ["#" "Op" "Matched substrings" "Advisor field that carried them"]
            (mapv (fn [{:keys [id request] :as run}]
                    (let [p (:proposal (final-state run))
                          ms (scope-matches p)
                          carried (->> [[:summary (:summary p)] [:rationale (:rationale p)]
                                        [:value (sorted-map-str (:value p))] [:op (str (:op p))]]
                                       (filter (fn [[_ v]]
                                                 (let [lv (str/lower (str v))]
                                                   (some #(str/includes? lv %) ms))))
                                       (map (comp #(code (str ":" (name %))) first)))]
                      (row (code id)
                           (code (str ":" (name (:op request))))
                           (str/join ", " (map #(str "<code>" (esc %) "</code>") ms))
                           (if (seq carried) (str/join ", " carried) (muted "n/a")))))
                  scoped)))))

(defn- committed-section [db runs report]
  (let [stored (vec (store/coordination-log db))
        pairs (:pairs report)]
    (section
     "Committed coordination log (the SSoT)"
     (str "Exactly what " (code "nursingcareops.store/coordination-log") " returned after the run &mdash; "
          (code (str (count stored))) " records from "
          (code (str (:committed-run-count report))) " runs that reached the "
          (code ":commit") " node. The " (code ":commit") " node is the only node in the graph that "
          "writes the SSoT. The approver column is derived per-record (see the next section), never "
          "assumed.")
     (table ["#" "Op" "Resident" "Committed payload" "Approver" "Retained where"]
            (mapv (fn [{:keys [run stored stored-approver run-approver]}]
                    (let [payload (or (:payload stored) (:value stored))]
                      (row (code (:id run))
                           (code (str ":" (name (:op stored))))
                           (code (:resident-id stored))
                           (esc (sorted-map-str (dissoc payload :resident-id :approved-by)))
                           (cond
                             stored-approver (ok (esc (first stored-approver)))
                             run-approver (str (esc run-approver) " "
                                               (warn "(audit only &mdash; not retained in the store record)"))
                             :else (muted "auto-commit &middot; no approval required"))
                           (cond
                             stored-approver (code (second stored-approver))
                             run-approver (muted "run audit channel only")
                             :else (muted "n/a")))))
                  pairs)))))

(defn- retention-section [report]
  (let [{:keys [approved-count retained-count dropped-keys all-identical?
                paired? stored-count committed-run-count]} report
        clean? (and paired? all-identical? (= approved-count retained-count))]
    (section
     "Approver retention &mdash; what this repo's store actually does"
     (str "This section is COMPUTED at render time by diffing the record each run handed to "
          (code "store/commit-record!") " (off the run's own final state) against the record "
          (code "store/coordination-log") " gave back. It is not a hardcoded statement about this "
          "repo, so it stays true if the store changes. Some sibling actors' stores destructure "
          (code ":value") " and silently drop " (code ":payload") ", which loses the approver; whether "
          "THIS one does is measured below rather than assumed.")
     (table ["Question" "Measured answer"]
            [(row "Records offered to the store vs. records the store returned"
                  (if paired?
                    (ok (str (esc committed-run-count) " offered, " (esc stored-count) " returned &mdash; paired 1:1"))
                    (critical (str (esc committed-run-count) " offered, " (esc stored-count) " returned &mdash; NOT paired"))))
             (row "Is every stored record byte-identical to the record the graph offered?"
                  (if all-identical? (ok "yes &mdash; the store drops nothing") (critical "no")))
             (row "Keys dropped by <code>store/commit-record!</code>"
                  (if (seq dropped-keys)
                    (critical (str/join ", " (map #(code %) dropped-keys)))
                    (ok "none")))
             (row "Commits that carried a human approver"
                  (code (str approved-count)))
             (row "…of which the approver survived into the stored record"
                  (if (= approved-count retained-count)
                    (ok (str (esc retained-count) " of " (esc approved-count)))
                    (critical (str (esc retained-count) " of " (esc approved-count)))))
             (row "Conclusion"
                  (if clean?
                    (ok (str "This store RETAINS the approver. "
                             "<code>nursingcareops.operation</code>'s <code>:request-approval</code> node puts "
                             "<code>:approved-by</code> on the record's <code>:payload</code>, and "
                             "<code>MemStore/commit-record!</code> conj's the whole record, so nothing is lost."))
                    (critical (str "This store DROPS part of the record. Approvers shown above with the "
                                   "&quot;audit only&quot; label exist in the run's audit channel but not in the SSoT."))))
             (row "Caveat this page will not hide"
                  (str (warn (str "The " (code ":approval-granted") " audit fact is NOT written to the store ledger at all."))
                       " " (code "nursingcareops.operation") "'s " (code ":request-approval")
                       " node emits it into the graph's audit channel, but only the " (code ":commit")
                       " and " (code ":hold") " nodes call " (code "store/append-ledger!")
                       ". So the ledger table below shows no approval-granted rows &mdash; that is a real gap "
                       "in this actor's ledger, not a rendering omission."))]))))

(defn- ledger-section [db]
  (let [l (vec (store/ledger db))]
    (section
     "Audit ledger (append-only, this run)"
     (str "All " (code (str (count l))) " facts " (code "nursingcareops.store/append-ledger!")
          " wrote, in order. Written only by the " (code ":commit") " and " (code ":hold") " nodes.")
     (table ["#" "Fact" "Op" "Resident" "Actor" "Disposition" "Basis"]
            (map-indexed
             (fn [i {:keys [t op resident-id actor disposition basis phase-reason]}]
               (row (code (str (inc i)))
                    (case t
                      :committed (ok (code ":committed"))
                      :governor-hold (if phase-reason
                                       (warn (code ":governor-hold"))
                                       (critical (code ":governor-hold")))
                      :approval-rejected (warn (code ":approval-rejected"))
                      (code (str ":" (name t))))
                    (code (str ":" (name op)))
                    (code resident-id)
                    (code actor)
                    (code (str ":" (name disposition)))
                    (cond
                      (seq basis) (str/join ", " (map #(code (str (if (keyword? %) (str ":" (name %)) %))) basis))
                      phase-reason (str (code (str ":" (name phase-reason))) " " (muted "(phase gate)"))
                      :else (muted "(none)"))))
             l)))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator-console document from a `run-demo!`
  result. Pure: same input map in, same string out."
  [{:keys [db runs]}]
  (let [report (retention-report db runs)
        hs (hard-holds db)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\">\n"
     "<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
     "<meta name=\"color-scheme\" content=\"light\">\n"
     "<title>cloud-itonami-isic-871 &middot; residential nursing care &mdash; Operator Console</title>\n"
     "<style>" (jp-go-dds.skin/dds+skin) "</style>\n"
     "</head>\n"
     "<body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Residential nursing care facilities (ISIC 871) &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; generated at build time by <code>nursingcareops.render-html</code> "
     "from a real <code>nursingcareops.operation</code> run &middot; "
     (str (count hs)) " HARD governor holds &middot; no hand-written rows</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>What this page is</h2>\n"
     "    <p>This actor coordinates the BACK OFFICE of a skilled nursing facility &mdash; resident-note "
     "logging, family/guardian visit scheduling, non-medication supply coordination, staff shift "
     "proposals and safety-concern flagging. It never administers medication, never performs nursing "
     "assessment or clinical diagnosis, never modifies a care plan, never touches wound/IV/catheter "
     "care, vital signs, physical restraint or end-of-life decisions, and never overrides a safety "
     "authority. Those are permanent HARD blocks in <code>nursingcareops.governor</code>, not "
     "unimplemented features.</p>\n"
     "    <p class=\"muted\">Regenerate with <code>clojure -M:dev:render-html</code>. The generator drives the "
     "real actor stack and <strong>throws</strong> if the run produces no HARD governor hold, so this "
     "page cannot silently decay into a mock. It is deterministic: no clock, no randomness, no network, "
     "and payload maps are rendered key-sorted, so two consecutive runs are byte-identical.</p>\n"
     "  </section>\n"
     (residents-section db)
     (phase-section)
     (governor-section)
     (timeline-section runs)
     (hard-holds-section db)
     (soft-holds-section db)
     (scope-evidence-section runs)
     (committed-section db runs report)
     (retention-section report)
     (ledger-section db)
     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-871 &middot; AGPL-3.0-or-later &middot; every number on this page was produced by "
     "the run that produced this file.</p>\n"
     "</footer>\n"
     "</body>\n"
     "</html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (hard-holds db)]
    ;; Build-time invariant, not a convention: a console that shows no
    ;; real HARD hold is not evidence of a governor. Precedent: isic-2513.
    (when (empty? hs)
      (throw (ex-info (str "no HARD :governor-hold fact on the ledger — refusing to write a console "
                           "that shows no real hold")
                      {:ledger-facts (count (store/ledger db))
                       :runs (count runs)})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count runs) " requests, "
                  (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds ["
                  (str/join " " (sort (distinct (map name (mapcat :basis hs)))))
                  "], "
                  (count (store/coordination-log db)) " committed records)"))))
