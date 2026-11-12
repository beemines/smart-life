# P1 order lifecycle implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Execute inline with TDD; one independent whole-branch review after implementation.

**Goal:** Finish the ten reviewed P1 rows with customer/merchant lifecycle, durable inventory returns, deadlines and actual cache evidence.
**Architecture:** Reuse order controller, JdbcTemplate transactions, existing permissions, Redis Lua, RocketMQ and info.html dialogs. Add focused lifecycle persistence/service/recovery and DTOs. Keep acceptance pipeline separate.
**Tech Stack:** Existing JDK21/Boot3/MyBatisPlus/MySQL/Redis/RocketMQ/Vue3; no new runtime dependencies.
**Spec:** docs/superpowers/specs/2026-11-12-p1-order-lifecycle-design.md

## Global constraints
- Preserve one-user-one-voucher including cancelled/refunded; mock funds only; UTC deadlines and Asia/Shanghai input conversion.
- Existing checkout on feature branch; keep IDEA paths and audit workspace. User already approved review scope and push; no redundant permission loop.
- Only smart-life-upgrade synthetic test services. Native implementation, no per-task implementer agents.
- Frontend/backend actual tests before main push; all final3CI green. No false checked todo or invented benchmarks.

## Review focus
- Old voucher edits/deletion must not change an existing order's price/shop/use period or allow cross-shop redeem.
- Return Redis applied then SQL ack lost; preserve same-user eligibility and prevent double SQL/Redis increment.
- SQL row contention at exact payment/use cutoff, refund/redeem and pay/timeout races.
- Durable jobs after broker outage, early/late/duplicate close delivery, and a poisoned oldest recovery row.
- Vue error/refresh handling and cursor isolation across filters/login; numeric IDs must not lose precision.

## Task1: snapshots, owner queries and lifecycle actions
Files: additive SQL/fresh schema, Voucher/VoucherWrite/VoucherPersistence, VoucherOrderPersistence.create, new OrderLifecyclePersistence/Service, OrderView DTO, VoucherOrderController, OrderLifecycleIT.
Interfaces: service.mine(status,beforeTime,beforeId,limit), detail(id), merchantDetail(id), pay(id), cancel(id), refund(id), redeem(id), capabilities(); response Result with data records. Record OrderView fields id,voucherId,shopId,shopName,title,payValue,actualValue,status,createTime,payTime,useTime,refundTime,payDeadline,useBeginTime,useEndTime,closeReason,stockReturnState,canPay,canCancel,canRefund,canRedeem. List data {items,nextTime,nextId,hasMore}. UTC ISO timestamps with Z. IDs strings. API GET /voucher-order/mine,/detail/{id},/merchant/{id},/capabilities; POST /{id}/pay,/cancel,/refund,/redeem.
- [ ] Write HTTP behavior tests, observe missing-endpoint RED; implement migration, immutable snapshots, authenticated reads and SQL-row-serialized actions; GREEN focused and baseline.
- [ ] Test status/owner/filter/cursor, snapshot edits, duplicate operations, races and deadline/use boundaries; preserve insert TX rollback; commit.

## Task2: durable close/refund/stock return recovery
Files: OrderLifecycleRecovery, lifecycle TX methods, OrderCloseMQ producer/consumer, order_stock_return.lua, new test worker and OrderLifecycleIT.
Interfaces: persistence.capture(orderId) within existing create TX; service.afterCreated(id) after commit attempts close schedule; recovery.runBatch() calls schedulePending,closeDue,finishRefunds,syncReturns. Public testable service.recover() bounded, config SMARTLIFE_LIFECYCLE_ENABLED false fixtures/default true.
- [ ] RED: no double SQL/Redis return; retained Redis eligibility; failed send/Redis, ack-loss/restart, early/late/duplicate close, poison cooldown.
- [ ] Implement existing-stack delayed reminder and durable scans/returns; update original fixtures/migration without weakening assertions; full Java GREEN; commit.

## Task3: existing-page UI
Files: info.html, shop-detail.html, order lifecycle client js, frontend tests, assets checker.
- [ ] RED actual JS handlers for paged list/filter/cursor, guarded actions/refresh/errors, exact string IDs and authorization route use.
- [ ] Implement existing personal tabs/dialogs, explicit mock labels, merchant lookup/redeem, and shop panel link; frontend GREEN; commit.

## Task4: cache measurement, independent review and release
Files: test-only CacheWorkloadIT or runner/report, docs/screenshots, docs/validation, CI, README/config/todo.
- [ ] Measure hot/cold/expiry/contention/Redis-fault with synthetic workload and record hardware/setup/counts/P95, test correct final value, no invented before/after performance claim.
- [ ] Full backend+frontend+Nginx; one fresh reviewer, important findings RED->GREEN; packaged app actual browser customer/merchant scenarios, before/after screenshots.
- [ ] Push approved main, inspect exact-SHA all3CI, fix failures, update verified todo and final docs with their CI; record finish.
