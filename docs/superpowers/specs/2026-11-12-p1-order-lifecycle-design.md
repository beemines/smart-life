# P1 order lifecycle design

User approved the ten remaining P1 items in the preceding review and requested backend plus actual frontend testing before push. Extend current modules; no new frameworks, services or independent business pages. Implementation choices below are defaults made by the implementer within that approved scope.

## Approaches

Keep the current monolith, SQL transactions and RocketMQ, with narrow lifecycle methods and persistent recovery records (selected by the user's approval of the review). A general state-machine/payment framework adds dependencies and pages without solving this demo's needs; changing the one-user-one-voucher model would require a different product rule and migration, so retain it.

## Rules

- One user may hold one order per voucher for life, including cancelled/refunded orders. Returning inventory never releases that user's eligibility.
- Mock payment and full mock refund only; no financial provider or real funds. Refund allowed only for paid, unused, unexpired orders; replay of an already accepted refund stays idempotent.
- Keep status codes1 unpaid,2 paid,3 used,4 cancelled,5 refunding,6 refunded. Closing an unpaid order records USER_CANCEL or TIMEOUT. CREATED remains the acceptance result, separate from business status.
- Default payment window15minutes, configurable positive seconds. Payment deadline and voucher use window are independent of claim begin/end. Newly created vouchers accept optional useBeginTime/useEndTime; absent defaults to business-now through30days, at latest order capture fallback. Existing orders snapshot available voucher details by additive migration without changing stock/status. Unknown/missing voucher data remains non-actionable, not guessed payment amounts.
- Order stores immutable title, shop id/name, pay amount, face amount, use timestamps and payment deadline. Amounts are integer cents and IDs serialize as strings. Activity and use inputs use Asia/Shanghai; SQL audit/deadline storage UTC. No client-supplied amount/owner/shop decides authorization.
- Customer may list/detail/pay/cancel/refund only own orders. Filter status and use stable descending create_time/id cursor, limit1..50. Merchant lookup/redeem uses persisted shop ownership through ShopPermission, with explicit admin/owner capability for the existing personal-page merchant panel. Public/foreign detail returns not-found without leaking.
- All mutations serialize on the SQL order row, verify old status and deadlines using DB UTC time. Duplicate same successful action returns current state. Pay vs close, refund vs redeem are mutually exclusive. Refund transitions2->5->6 can resume after restart.
- Cancel/timeout and successful refund restore SQL inventory once and insert one per-order durable stock-return record in the same TX. After commit Redis Lua checks original order ownership and a per-order returned marker before increasing stock; retains eligibility. Missing/corrupt/unproven Redis state becomes REVIEW; no blind stock reseed. Redis success/SQL-ack failure replays safely.
- SQL order creation commits before delayed-close message. Persistent lifecycle schedule flag and recovery scan repair send failures. RocketMQ delay is a hint; receiver rechecks DB deadline/status. Scheduler scans overdue unpaid orders as fallback and processes refund/return records bounded with retry cooldown so one bad record does not starve others. Test hooks only under test sources.
- UI reuse info.html tabs and Element Plus dialogs: My orders (filters/load more/detail/pay/cancel/refund); authorized merchant panel lookup/redeem by order id. Keep current styling, show simulation and error messages, disable in-flight buttons; refresh authoritative detail/list on success. Existing shop acceptance panel links to My orders. No secret/credential in caches or screenshots.
- Cache work is measurement: reproducible hot/cold/concentrated expiry/concurrent-update/Redis-fault workload, count actual shop SELECT separately from sync checks, record latency P95 and fallback results. Do not assert unmeasured speedup or add cache components preemptively.

## Validation and delivery

TDD observed failure before production per task. Real MySQL/Redis/MQ integration covers owner isolation, snapshots after voucher edit, stable pagination, duplicate actions, pay/close and refund/redeem races, cutoff boundaries, recovery after Redis success before ack, repeat return/no re-claim, delayed message/fallback, migration and corrupt legacy. Preserve original127 regressions. Actual browser validates customer and merchant flows, success/refusal, status filters and logout; screenshots before/after. Full build and all exact-head CI green before final response. Keep original todo before text and mark only proven P1 rows. Commit and push to user's main after local tests/UI; no external messages.
