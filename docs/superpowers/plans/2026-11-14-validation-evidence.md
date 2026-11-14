# Remaining P2 implementation plan

> Use superpowers:subagent-driven-development with disjoint tasks and one exclusive integration-service lane. Root owns UI/docs/release; backend agent owns Java/tests; load agent owns scripts/load and performance docs. One scoped review per deliverable and one whole-branch review.

**Goal:** complete remaining applicable P2 correctness, measurement and presentation evidence.
**Spec:** docs/superpowers/specs/2026-11-14-validation-evidence-design.md
**Tech:** existing Java21/Boot3/MySQL/Redis/MQ/Vue3 plus official Apache JMeter CLI; no new app runtime dependencies.

## Constraints / review focus
Only named smart-life-upgrade containers and own synthetic records/keys/app/topic may mutate. No parallel Maven or load against same services. Preserve business semantics, authorization, bounded recovery and all old tests. Never expose test tokens in committed plans/results. Expected sale rejection is distinct from system error. Process exits vs boundary injection explicitly labeled. Cleanup only owned successfully inserted fixtures. No product-cache/microservice/cart expansion. Existing UI reused, before/after screenshots. Final-head all3CI green.

## Task 1 — backend diagnostics/concurrency
- [ ] Verify log missing via failing captured-log test; add correlated WARN without losing lease recovery.
- [ ] Split lifecycle races with ready barrier, explicit initial states, response and stock/return assertions; add HTTP100users/stock10 real MQ convergence, duplicates and zero negative inventory.
- [ ] Targeted GREEN, supported fullcleanverify + report, scoped review. Backend exclusive service lane until released; no demo/load concurrently.

## Task 2 — JMeter and final reconciliation
- [ ] Inspect current APIs/fixtures and official JMeter docs; build bounded local fixture+manifest+cleanup and JSON business assertions; tests for reconciler reject pending/contradictions/system errors.
- [ ] Offline while backend owns lane; after handoff run packaged isolated app, repeat HTTP query profiles and limited-stock purchase workload, capture environment/JTL/manifests redacted reports, drain and reconcile final SQL/Redis/intent outcomes.
- [ ] Commit reproducible scripts/plan/raw redacted evidence and docs, scoped review. No published tokens or production-capacity claim.

## Task 3 — root UI and evidence
- [ ] Read-only rating regression RED→disabled GREEN; desktop/mobile real interaction before/after screenshots.
- [ ] Fault matrix maps existing test names and actual mechanisms, failures and expected outcomes. Add evidence-backed project/resume/interview material with limitations.
- [ ] Full frontend checks, validation docs and accurate todo (original before cells preserved); scoped review.

## Release
One final independent whole-branch review; address blocking findings with regression verification. Push only after local/backend/frontend/browser/load gates; finish only with final-head three CI jobs green. Retain user checkout and audit artifacts.
