# Order safety implementation plan

> **For agentic workers:** Use superpowers:executing-plans inline, with one independent whole-branch review at the end.

**Goal:** Ship the two approved P0 fixes with reproductions, real-service verification and green published CI.

**Architecture:** Keep the existing ID API and order service. Correct timestamp/date computation, add schema-level uniqueness and preserve the transaction boundary. Existing lock/MQ/compensation policy stays unchanged.

**Tech Stack:** Java 21, Spring Boot 3.5.16, MyBatis-Plus 3.5.18.1, MySQL 8.4, Redis 8.4, existing test libraries.

**Spec:** `docs/superpowers/specs/2026-11-08-order-safety.md`

## Global constraints

- Only the approved two P0 items and their tests/documentation/CI are in scope.
- Preserve public APIs and existing lock, stock and MQ policy.
- No business-data deletion, counter reset or automatic production schema migration.
- One user/voucher pair can have only one order record; future reclaim requires a separate model decision.
- Tests operate on the dedicated development containers and synthetic fixtures.

## Review focus

- UTC epoch and date bucket use the same Instant; invalid timestamp/sequence values never silently overflow.
- Redis data loss is not presented as solved; operational limitations and restore precautions are explicit.
- The fresh schema and old-schema migration enforce the same user/voucher uniqueness rule.
- A duplicate insert or save failure cannot commit a database inventory deduction without an order.
- Tests preserve existing records and do not accidentally drop the real orders table or swallow unrelated duplicate-key failures.

## Task 1: Correct ID generation

Files: `utils/RedisIdWorker.java`, `utils/RedisIdWorkerTest.java`, `utils/RedisIdWorkerIT.java`.

- [x] Run the existing baseline suite. Expected: green unit tests.
- [x] Add tests using the existing public API for timestamp bits, sequence boundaries and Redis failures. Run them before production changes. Expected: incorrect time bits and missing boundary checks fail.
- [x] Add fixed-clock tests, implement the UTC Clock overload and bounded bit encoding. Expected: all ID unit tests pass.
- [x] Verify shared sequences and restart/date behavior against real Redis. Expected: unique IDs with only test-owned keys cleaned.
- [x] Record results and commit the independently tested fix.

## Task 2: Enforce database uniqueness

Files: `db/smart-life.sql`, `db/migrations/2026-11-08-order-user-voucher-unique.sql`, `service/impl/VoucherOrderServiceImpl.java`, unit save-failure test and `OrderSafetyIT.java`.

- [x] Add concurrent duplicate-pair integration checks and a save-false unit regression. Run against the old schema/code. Expected: duplicate rows are accepted / missing save failure is exposed.
- [x] Add the fresh-schema key and migration with duplicate report, plus save-failure exception. Expected: different pairs remain valid; duplicate pairs are rejected; stock rolls back on insertion failure.
- [x] Execute the real migration on legacy temporary tables with and without duplicates. Expected: clean legacy schema gains the key; duplicate legacy records remain unchanged and the migration rejects them.
- [x] Update CI's explicit integration class list, document safe migration and Redis recovery constraints, and update the approved TODO statuses with evidence.
- [x] Run full unit, integration, frontend and build verification. Expected: all green.

## Task 3: Review and publish

- [x] Request one independent read-only whole-branch review; fix material findings with reproductions and a green suite.
- [ ] Merge the verified branch and publish using the user's existing authorization.
- [ ] Confirm remote SHA equals the local publication and every check for that SHA succeeds. Record results outside the repo when no source change is needed.
