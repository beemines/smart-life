# Order request recovery implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:executing-plans, task by task with TDD and one independent final review.

**Goal:** Close durable acceptance, uncertain publication, owner-safe recovery and user-visible result handling; check only validated todo items.

**Architecture:** Add one request table and a small recovery audit table. Extend existing persistence/orchestration, reuse consumer, transaction and Lua; expose owner-checked status queries and a local recovery job/CLI. Reuse the existing voucher UI.

**Tech Stack:** Existing JDK21, Boot3.5.16, MyBatis-Plus3.5.18.1, Redisson4.8.0, RocketMQ5.5.1, MySQL8.4, Redis8.4, Vue3, Axios1.21.0. No new dependencies.

**Spec:** docs/superpowers/specs/2026-11-11-order-request-recovery.md

## Global constraints

- Existing endpoints/envelopes and lifetime claim rule remain; order IDs cross the browser boundary as strings.
- Only isolated smart-life-upgrade services and synthetic fixtures may be mutated.
- Keep original before descriptions; document actual after behavior and evidence.
- Screenshot every UI change and inspect final-commit CI before finishing.

## Review focus

- Same request key used concurrently or for another voucher must not reserve twice.
- A late send result must not overwrite a terminal consumer result.
- An absent reservation on a previously PENDING order must not be recreated blindly.
- Concurrent recovery workers must recheck staleness/terminal state under locks.
- A user cannot query another user's request; timeout/reload must preserve retry identity.

### Task 1: durable acceptance and publication

Files: request entity/mapper, existing persistence and service/interface/Lua; additive DDL; OrderRequestRecoveryIT.java.

Interfaces: seckillVoucher(Long,String); find request by owner/key/id; prepare/resume intent; terminal state written with order/failure.

- [x] Write failing tests for key replay, string IDs, uncertain send and same-owner reservation replay; observe RED.
- [x] Implement durable acceptance, safe preparation/publication and terminal projection; run focused tests.
- [x] Preserve legacy direct-delivery tests and adjust explicitly changed send-timeout semantics; commit tested behavior.

### Task 2: query, recovery and audit

Files: status DTO/controller, recovery service/job/CLI, worker test helper, recovery tests, application config/main.

- [x] RED tests for owner-only read, fresh WAIT, stale preparation/publication, repeated compensation and recovery audit.
- [x] Implement bounded preview/execute plus scheduling/CLI using existing locks and compensation; verify GREEN.
- [x] Halt real child JVMs after reserve and send using test-only boundary hooks; recover with same IDs and check final stock/rows; commit.

### Task 3: existing UI and evidence

Files: common.js, shop-detail.html, small order-request client module, JS tests, screenshots under docs/screenshots/order-recovery.

- [x] RED actual JS timeout handler, no premature success, finite polling, persistent key and large string ID tests.
- [x] Implement existing-page status panel/continue action and bounded polling; verify frontend checks/tests.
- [x] Run app and capture real processing/created/rejected screenshots; inspect them; commit.

### Task 4: review and delivery

- [x] One independent review; fix important findings with failing regressions then full green suite.
- [x] Full build/unit/integration/JS tests; update migrations, operations, validation and roadmap.
- [ ] Push approved update, inspect all CI, fix failures; only then mark the four remaining P0 rows complete and verify final CI again.
