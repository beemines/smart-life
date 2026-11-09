# Order consumption safety implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Safely distinguish retry, completed order and durable business rejection while keeping the user lock through transaction completion.

**Architecture:** Existing public order service orchestrates lock and compensation. A small transactional persistence bean handles order-ID checks, the existing stock/insert transaction and a durable rejection table. Existing Lua scripts gain owner-checked compensation; existing MQ callback becomes directly testable.

**Tech Stack:** Existing JDK 21, Spring Boot 3.5.16, MyBatis-Plus 3.5.18.1, Redisson 4.8.0, RocketMQ client 5.5.1, MySQL 8.4, Redis 8.4. No new dependencies.

**Spec:** docs/superpowers/specs/2026-11-09-order-consumption-safety.md

## Global constraints

- Preserve HTTP endpoints/envelopes and lifetime one-user-one-voucher rule.
- Only isolated smart-life-upgrade test services and synthetic records may be modified.
- Preserve the four reviewed problem statements in documentation.
- Use TDD and verify all CI jobs on the final pushed commit.

## Review focus

- An old failed delivery must not become a successful purchase after restocking.
- Compensation replay must not inflate stock or release another order's eligibility.
- A send timeout racing a consumer commit must not undo the successful reservation.
- A reused order ID with different payload must not be acknowledged as an ordinary duplicate.
- Unverifiable legacy Redis ownership must be durable/manual, never guessed.

### Task 1: Reproduce and implement transaction and identity handling

Files: OrderConsumptionIT.java; VoucherOrderServiceImpl.java; IVoucherOrderService.java; new OrderProcessingResult.java, VoucherOrderPersistence.java, VoucherOrderFailure.java, VoucherOrderFailureMapper.java; additive schema and migration.

Interfaces: createVoucherOrder(VoucherOrder) -> OrderProcessingResult; persistence.create(VoucherOrder) and persistence.rejectUnconfirmedSend(VoucherOrder) are transactional, return the same result; failures keyed by order ID.

- [x] Write real integration regressions for held lock, masked ID mismatch, and rejection replay after restock.
- [x] Run tests on unchanged production code; capture expected assertion failures.
- [x] Implement outer lock, transactional persistence, explicit results, durable rejection and additive DDL.
- [x] Update existing service tests for the new transaction boundary; run unit and targeted integration tests; commit.

### Task 2: Reservation compensation and MQ delivery handling

Files: seckill.lua, seckill_rollback.lua, VoucherOrderReservation.java, existing order service and consumer, OrderConsumptionIT.java, VoucherOrderMQConsumerTest.java.

Interfaces: reservation.reserve(VoucherOrder) -> long; reservation.compensate(VoucherOrderFailure) -> pending/done/manual; consumer.consumeMessage(List<MessageExt>, ConsumeConcurrentlyContext) -> ConsumeConcurrentlyStatus.

- [x] Write failing real Redis tests for ownership, repeated compensation, interruption/replay, preserved eligibility and legacy/missing-stock cases; test MQ retry/ACK behavior.
- [x] Observe RED, implement owner checks and the durable-failure handshake using the existing Lua and MQ flows.
- [x] Cover producer send-uncertainty fencing under the same lock; no unconditional reservation release.
- [x] Verify ten deliveries, concurrent deliveries, post-commit replay, real broker lock contention and send-uncertainty race. Run complete local unit and integration suites; commit.

### Task 3: Review, documentation and delivery

- [x] One independent whole-branch review, fix important findings with RED/GREEN tests and a green complete suite.
- [x] Update migration/recovery guide, four problem statements, validation record and only verified todo entries.
- [x] Run clean verify, all real integration suites and frontend checks; commit.
- [x] Fast-forward approved main delivery, push, inspect every final-commit CI job and fix any failure before completion. Code commit de3ab7c passed all three jobs in run 42638777873; verify the documentation-only closing commit as well.
