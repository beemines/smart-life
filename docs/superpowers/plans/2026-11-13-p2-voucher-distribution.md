# P2 voucher distribution implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL superpowers:subagent-driven-development. One backend implementer handles coherent persistence, import and ordering transaction unit; root owns disjoint frontend/verification/release. Scoped review per deliverable and one final whole-branch review.

**Goal:** persistent batch paid-voucher distribution using existing order chain and merchant page.
**Architecture:** JDBC task/row records, recipient-aware existing intent/reserve/send, existing MQ consumer, bounded recovery, POI event import. No new page.
**Tech stack:** existing JDK21/Boot3/MySQL/Redis/RocketMQ/Vue3; POI6.0.0 for xlsx only.
**Spec:** docs/superpowers/specs/2026-11-13-p2-voucher-distribution-design.md

## Global Constraints
- User selected unpaid orders requiring payment; never free grants. Lifetime one-user-one-voucher retained.
- Existing checkout on feature branch, no unrelated files/services. Never print credentials. IDs response strings. No fabricated performance claim.
- Follow spec exact API; reuse current page, permissions and ordering; no UserHolder impersonation. HTTP/MQ success alone never marks batch row successful.
- Backend and actual frontend tests before push; committed UI screenshots, independent review, final-head all3CI green. Update only verified todo scope.

## Review Focus
- Send response lost or crash between persistent relation/reserve/send/consume/row result: same ID on recovery, no extra stock.
- Two workers, duplicate input and cross-task same recipient: business uniqueness plus safe progress records.
- Revoked shop permission, foreign task, voucher status/window changes before dispatch.
- XLSX shared strings, formula/precision loss, ZIP amplification, malformed final row and partial import.
- UI concurrent refresh/task selection, stale detail and large string IDs.

## Task1: durable distribution backend and xlsx ingestion
Files: new VoucherBatch* service/controller/DTO/parser, new DB migration+fresh schema, existing VoucherOrderServiceImpl recipient-aware helper, pom.xml, new VoucherBatchIT and parser tests; CI includes test. Exact APIs and records are spec Fixed API contract.
- [ ] Write HTTP missing-endpoint and recovery failure tests, observe RED; create bounded task/row persistence and explicit merchant/recipient checks.
- [ ] Implement stable IDs+existing order chain, test duplicate/uncertain send/restart/two-workers/permanent vs transient states, real MQ eventual success and stock.
- [ ] Add POI6.0.0 event xlsx parsing after official docs; tests for mixed rows/duplicate/header/formula/limits/invalid-last-row and bounded batches; test memory measurements in isolated child if needed.
- [ ] Assess list indexes with actual query plans; test original snapshots/payment unchanged; commit and report RED/GREEN commands and evidence.
- [ ] Scoped spec+quality review, important fixes and scoped re-review.

## Task2: existing-page merchant workflow
Files: frontend/js/voucher-batches.js, order-lifecycle.js mount, bootstrap if needed, info.html script/css; scripts/batch-client.test.mjs and package test/check config.
- [ ] Actual JS client RED for busy guard/string IDs/idempotency key reuse/errors and late responses; component template compile.
- [ ] Implement exact API, new inline existing merchant area task and row views, import/manual submit, controlled retry and paging. No separate page.
- [ ] Frontend checks/tests GREEN; scoped review; commit.

## Task3: complete validation and release
Files: docs/validation, screenshots, operations/README/config, todolist.
- [ ] Stop own demo before isolated full suite. All previous+new tests clean verify; frontend+Nginx.
- [ ] Packaged actual app real browser: merchant manual/xlsx submit, mixed outcomes, details/paging/refresh, recipient unpaid order/pay, unauthorized denial, mobile; screenshots and SQL/Redis counts.
- [ ] One whole-branch independent review, fix important findings with regression tests and scoped re-review.
- [ ] Push own main only after tests, exact-head CI green. Update accurate todo, push docs, final CI green, finish evidence.
