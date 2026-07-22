# Governance

Maintained by the cloud-itonami org (gftdcojp). Decisions land as ADRs in the
superproject ledger (see ADR-2607121000 for the ISIC Wave definition placing
ISIC 871 in Wave 4, and ADR-2607152500 for the Wave 4 rollout amendment and
quality guardrails this repo follows). The actor pattern (advisor-LLM sealed
behind an independent governor, append-only audit ledger) is non-negotiable:
the governor gates every proposal; direct resident-facing actuation, any
clinical/nursing assessment, medication/pharmaceutical decision, care-plan
change, or end-of-life decision is permanently blocked; `:flag-safety-concern`
(resident safety incidents — falls, wellbeing concerns) always escalates to
human review, regardless of confidence.
