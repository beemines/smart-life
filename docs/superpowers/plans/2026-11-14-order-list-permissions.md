# Order list permission implementation plan

> Use superpowers:executing-plans inline; one final independent review.

**Goal:** eliminate repeated list grant queries without weakening redemption checks.
**Architecture:** request-local bounded candidate-shop set, one grant read, explicit view permission.
**Tech Stack:** existing Java21/Boot3/JDBC/MySQL/Vue3; no new APIs or dependencies.
**Spec:** docs/superpowers/specs/2026-11-14-order-list-permissions-design.md

## Constraints and review focus
Keep paid voucher business only. No cached grants across requests. No permission query for pages without actionable rows. OWNER scoped to actual snapshot shop; ADMIN shop0 only. Actual redemption checks current grant in transaction. Preserve pagination/IDs/snapshots and original todo before cells. No unrelated services or data modified.

## Implementation
- [ ] Add real integration SQL-count and ordinary/OWNER/ADMIN/revoke regressions, observe RED.
- [ ] Add bounded request-local grant load in OrderLifecyclePersistence; pass to list views in OrderLifecycleService. GREEN targeted regression.
- [ ] Full supported Java and frontend checks, real packaged browser, evidence; one whole-branch review and fixes if required.
- [ ] Update accurate todolist scope; push after local gates, require exact final-head all CI green before finish.
