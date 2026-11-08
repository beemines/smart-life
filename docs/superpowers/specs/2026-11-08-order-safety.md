# Order ID and database duplicate protection

## Approved scope

The user approved implementing and pushing the two reviewed P0 items: correct the order-ID timestamp and add database protection for duplicate user/voucher orders. The preceding annotated code review is the approved design; do not reopen its approval step.

- Keep Java 21 / Boot 3 and the existing Redis, MyBatis, Redisson and RocketMQ stack.
- Keep `nextId(String)` and all HTTP contracts. Obtain the timestamp and UTC date bucket from one `Instant`; encode 31 positive timestamp bits and 32 sequence bits, rejecting invalid ranges. A package-visible Clock overload enables deterministic tests.
- Retain Redis `INCR` and its existing key prefix. Do not introduce TTL or silently reset counters. Document that uniqueness requires retained Redis sequences and how to stop/recover safely after sequence loss; arbitrary Redis data loss is not solved by this patch.
- Keep the current one-user/one-voucher order-record rule, including cancelled/refunded rows. Reclaim rules remain a later design change, explicitly requiring a different constraint model.
- Add a named unique key on `(user_id, voucher_id)` to the fresh schema and a one-time existing-schema migration. Report duplicate pairs before DDL; do not delete or rewrite business records. MySQL's index build must reject existing duplicates.
- Retain the existing user lock, duplicate query, stock condition and transaction. Throw if saving the order reports failure so the database deduction cannot commit alone. Do not swallow duplicate-key exceptions inside the transaction or classify unrelated primary-key collisions as business duplicates.
- Do not expand this change into MQ ACK/lock retry policy, Redis compensation, payment/refunds, auth or frontend changes.

## Verification

- Unit tests: time bits, fixed-clock date boundary and UTC independence, same-second concurrency, cross-second behavior, application object recreation with retained counter, range rejection and Redis failures.
- Real Redis: two worker instances sharing one key and fixed clocks across dates; clean only test-prefixed counters.
- Real MySQL: concurrent inserts with different IDs for the same pair; other pairs allowed; primary-key collision rolls back the service's database stock deduction. Test the actual migration on connection-local legacy temporary tables, including existing duplicate records remaining untouched after refusal.
- Keep existing stack integration checks and include the new integration classes in CI. Obtain one independent final review, run final verification, push and check exact-head CI.

## Implementation choices

Native managed worktree creation did not return a result. Continue on the dedicated branch `fix/order-id-unique-guard` in the existing checkout, retaining the user's untracked `todolist.md`.

Context7 is unavailable. Consulted JDK 21 Clock, Spring 6.2 transaction and MySQL 8.4 unique-index official documentation instead. No new runtime dependencies.
