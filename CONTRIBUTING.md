# Contributing

**Maturity: `:implemented`** — `src/nursingcareops/` implements the reference
NursingCareAdvisor / NursingCareGovernor actor, composed by
`nursingcareops.operation/build` into a real compiled `langgraph-clj`
`StateGraph` (`intake -> advise -> govern -> decide -+-> commit /
request-approval -> commit / hold / escalate`) with `interrupt-before
#{:request-approval}` and checkpoint-based human-in-the-loop resume.
Contributions that extend coverage are welcome: a Datomic/kotoba-server
`Store` backend, a real LLM `Advisor` implementation, additional Governor
scope-exclusion rules, and a persistent (non-in-memory) `Checkpointer` for
production deployments. Open an issue or PR. License: AGPL-3.0-or-later.
