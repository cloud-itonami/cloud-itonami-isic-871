# cloud-itonami-isic-871: Residential Nursing Care Facilities

ISIC Rev.4 Code 871 — Skilled nursing care facilities (post-acute, rehabilitation nursing, chronic care)

A **coordination-only** operations actor for backend workflow and proposal governance in skilled nursing care facilities. Scope excludes all clinical/nursing assessment decisions, medication administration, care-plan changes, and end-of-life decisions.

## Operations (Closed Allowlist)

All effects are `:propose` (proposals only, no direct actuation):

1. **`:log-resident-note`** — Routine daily observation logging (meals, mood, activity, hygiene). Never a clinical/nursing assessment.
2. **`:schedule-family-or-guardian-visit`** — Family/guardian visit scheduling coordination.
3. **`:coordinate-supply-request`** — Non-medication consumables only (linens, mobility aids, food, incontinence supplies). Absolutely no pharmaceuticals, medical devices, or clinical supplies.
4. **`:schedule-staff-shift-proposal`** — Proposal only; finalization is done by human shift supervisors.
5. **`:flag-safety-concern`** — Safety incidents (falls, wellbeing concerns). **ALWAYS escalates** to human review.

## Governor: Three HARD Checks

1. **Resident unverified** — Must be independently `:registered?` and `:verified?` in store. Never self-reported.
2. **Effect not `:propose`** — Every proposal must be `:propose`, never direct actuation.
3. **Scope exclusion** — Permanently blocks proposals touching: medication/pharmaceutical, nursing assessment, clinical diagnosis, care-plan changes, wound care, IV/catheter management, vital signs, physical restraint, end-of-life, or safety-authority decisions. Extra-conservative for clinical adjacency of skilled nursing.

## Phase Rollout (0→3)

- **Phase 0**: Read-only (no writes)
- **Phase 1**: Resident-note logging only, human approval required
- **Phase 2**: Add family visit + supply request + shift proposals, still approval
- **Phase 3**: Auto-commit for resident-note, visit, supply, shift; `:flag-safety-concern` always escalates

## Architecture

```
store/    ← SSoT (MemStore, string-keyed resident directory)
advisor/  ← Contained inference node (mock + real LLM seam)
governor/ ← Independent compliance censor (HARD checks)
phase/    ← Rollout gate (phases 0→3)
operation/ ← langgraph-clj StateGraph (intake→advise→govern→decide→commit|hold|escalate)
sim/      ← Demo driver
```

All modules are `.cljc` (portable across Clojure/ClojureScript/nbb). Tests run offline, deterministic, no external deps.

## Running Tests & Demo

```bash
# Run full test suite
clojure -M:test

# Run lint
clojure -M:lint

# Run demo/simulation
clojure -M:run
```

## License

AGPL-3.0-or-later. See LICENSE file.

## References

- ADR-2607152500 (Wave 4 rollout amendment, quality guardrails)
- ADR-2607121000 (ISIC Wave definition, ISIC 871 as Wave 4)
- ADR-2607152300 (ISIC-0520 lignite mining, verified-redo pattern reference)
- kotoba-lang/industry registry entry for ISIC-871
