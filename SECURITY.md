# Security Policy

This repository contains a reference actor implementation (in-memory
`MemStore`, mock `Advisor`, in-memory `Checkpointer`, no real backend
integration, no credentials, no resident/patient data). If a production
deployment (real Datomic/kotoba-server store, real LLM advisor, persistent
checkpointer, real resident/facility data) introduces vulnerabilities, report
privately to root@junkawasaki.com. Do not open public issues for security
reports.
