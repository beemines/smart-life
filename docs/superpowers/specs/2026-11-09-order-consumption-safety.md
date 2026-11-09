# Order consumption safety

User approval: “你去完成”, following the four reviewed problems. Keep the four problem descriptions, distinguish normal duplicate delivery from exceptional conflicting order IDs, and reuse existing flows.

## Scope and approach

1. Lock contention must escape as a retryable failure, never a successful no-op.
2. Check order ID and matching user/voucher before user/voucher uniqueness. Preserve the lifetime one-user-one-voucher rule and database constraints.
3. Persist deterministic rejection before Redis compensation. Replays of rejected orders must remain rejected, even if inventory later changes. Compensation must identify the reservation owner and be atomic/idempotent; uncertain legacy ownership is retained for manual review rather than guessed.
4. Hold the user lock until the database transaction has committed or rolled back. Use a small separate transactional persistence bean; keep lock orchestration in the existing public order method so MQ and direct callers share the protection. No new business page, middleware, or dependency.

The alternatives were a lock only in the MQ callback, programmatic transactions, or an outer service lock plus a separate transactional bean. The last approach also protects direct calls and the producer's failure path without self-invocation/circular injection. It implements the approved transaction boundary with a small internal placement change.

## Interfaces and storage

- Existing HTTP endpoint and Result envelope stay unchanged. Internal createVoucherOrder returns CREATED / ALREADY_PROCESSED / REJECTED.
- New tb_voucher_order_failure: order_id primary key, user_id, voucher_id, reason, retained_order_id, compensation_status (pending/done/manual), timestamps. It records deterministic failures, not a general-purpose workflow.
- Retain Redis eligibility Set; add a per-voucher reservation-owner Hash mapping user ID to order ID. Reserve and compensate in existing Lua scripts.
- Compensation releases only an owned reservation. For a user/voucher conflict it preserves the prior successful order's eligibility. Missing/invalid stock or unverifiable legacy ownership produces a durable manual record, never an invented stock value.
- Producer send exceptions are ambiguous: coordinate rejection under the same user lock and transaction, check whether the order already succeeded, then persist SEND_FAILED before compensating. Delayed delivery reads that terminal failure. If the lock/database is unavailable, retain the reservation and report uncertainty with the order ID; do not claim exactly-once publication. A full transactional outbox remains outside scope.
- MQ reuses finite retries and its dead-letter queue for transient/integrity failures. Log order/message identity and delivery count. A pending compensation must not be ACKed; manual cases are durably recorded and explicitly logged.

## Acceptance

Real MySQL/Redis/RocketMQ tests: ten deliveries, concurrent deliveries, commit-before-ACK replay, held lock then retry, ID/payload mismatch, conflicting request IDs, stock exhaustion with durable rejection, replay after restock, compensation interrupted after Redis change before database acknowledgement, owner mismatch, preserving an old order's eligibility, legacy ownership and missing stock. Migration must be additive and preserve existing orders.

## Boundaries

No payment/refund/reclaim redesign, no new UI, no fabricated crash process claim: test commit-before-ACK by committing the production service and redelivering without acknowledging the original. Real broker coverage additionally verifies lock retries. Redis data loss, broker retention/DLQ operations and an outbox for producer outages remain operational boundaries.
