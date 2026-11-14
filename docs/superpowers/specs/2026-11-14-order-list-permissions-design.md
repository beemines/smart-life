# Order list permission reads

User authorized the reviewed request-local permission reuse and push after tests, and explicitly selected keeping paid-voucher purchase and redemption only. No cart, discount settlement, template cache, frontend layout or dependency changes. Preserve existing checkout/IDEA paths on a feature branch, matching the established workflow.

Approaches: cross-request cache risks stale revoked grants and has no measured need; joining grants into every list row still repeats lookups. Select bounded distinct candidate shops (at most the existing 50-row page), read relevant OWNER and global ADMIN grants once per request, pass the resulting permission into view assembly. Empty/pending/nonredeemable pages make no permission lookup. Detail and transactional redemption retain authoritative current checks. User approved this direction in the preceding code review and follow-up.

Acceptance: twenty paid current vouchers across two shops need one grant SQL; ordinary/OWNER/ADMIN/revoked users see accurate canRedeem values. A previously enabled button never authorizes redemption after revoke. String IDs, filters, cursors and price snapshots unchanged. No invented latency or capacity claims. Record backend/frontend/browser validation and exact final-head green CI before delivery.
