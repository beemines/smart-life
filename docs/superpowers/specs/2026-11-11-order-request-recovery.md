# Durable order acceptance, recovery and result UI

Approval: user requested “帮我解决以上问题，然后再todolist打勾” after reviewing the shared acceptance-record approach. Reuse existing service, locks, transaction bean, Lua and merchant-detail page. No separate business page, new middleware or workflow library.

## Design

- Persist a request before touching Redis: order_id, user_id, voucher_id, unique (user_id, request_key), state PREPARING/PENDING/CREATED/REJECTED/REVIEW, send attempts/error and timestamps. A retried HTTP request with the same Idempotency-Key returns the same order; key reuse for another voucher is rejected.
- Reserve under existing order-ID/user locks. Lua recognizes a reservation already owned by the same order without decrementing twice. PREPARING can resume reservation; PENDING with missing ownership is REVIEW, not a speculative second reservation.
- Persist PENDING before publishing. Publish outside locks. Uncertain send preserves its reservation and durable intent; later retries use the same order ID. Existing consumer idempotency handles duplicates. No immediate rejection merely because a send response is lost.
- Consumer's order/stock transaction updates request terminal state together with success/rejection. Existing failure records remain the authority for compensation; manual cases project REVIEW. Lock contention remains retryable. Unknown or contradictory state fails closed.
- POST /voucher-order/seckill/{voucherId} keeps the existing Result envelope and returns data as an order-ID string. Optional Idempotency-Key header. Authenticated GET /voucher-order/result/{orderId} and /voucher-order/request/{requestKey} share the same status DTO. Unknown/other-user IDs produce the same error, and queries do not mutate data.
- Recovery previews requests, durable pending compensations and per-voucher stock/ownership context. Fresh in-flight work is WAIT, not a failure. Execute rechecks age and terminal state under the same locks, resumes preparation, republishes the same ID, or continues existing compensation. REVIEW is never silently repaired. Append a recovery audit row before an action and finish its outcome afterwards; a crash leaves a visible RUNNING record.
- Use existing Spring scheduling (60s delay, stale threshold 120s) plus an explicit local CLI preview/execute entry. No public admin/recovery endpoint. CLI disables the MQ consumer and closes the application context on completion. Configurable bounded batches; test fixtures disable the scheduled job.
- Reuse each voucher's existing UI for processing/created/rejected/review states, finite polling and manual continue. Keep the request key across network errors and page reloads; scope browser storage to the login session. Fix missing error.response, avoid treating timeouts as rejection, and transmit order IDs as strings. Add screenshots of actual page states.

## Validation

TDD: keyed request reuse and ownership checks, failed send/late delivery, reservation replay, pending-owner loss, safe preview/execute and repeated recovery, fresh in-flight exclusion, audit, compensation 1 vs 10 repetitions and successful/nonexistent requests. Real isolated MySQL/Redis/MQ integration. Child JVM runs production submission with test-only boundary hooks and halts after reserve / after send, then recovery proves convergence. Browser screenshots for processing, created and rejected states; JS tests run actual client modules. Full unit/integration and final-commit CI must pass before marking the four remaining P0 rows complete.

## Boundaries

One-user-one-voucher lifetime rule unchanged. No payment/refund redesign, speculative global stock repair, automated recovery from arbitrary Redis data loss, new business pages, or new dependencies. Manual repair remains necessary when ownership/stock cannot be established. Apply additive migrations and coordinate cutover of old producers/consumers; preserve rejection records.
