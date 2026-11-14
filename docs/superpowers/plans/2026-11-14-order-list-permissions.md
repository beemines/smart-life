# Order list permission implementation plan

> Use superpowers:executing-plans inline; one final independent review.

**Goal:** eliminate repeated list grant queries without weakening redemption checks.
**Architecture:** request-local bounded candidate-shop set, one grant read, explicit view permission.
**Tech Stack:** existing Java21/Boot3/JDBC/MySQL/Vue3; no new APIs or dependencies.
**Spec:** docs/superpowers/specs/2026-11-14-order-list-permissions-design.md

## Constraints and review focus
Keep paid voucher business only. No cached grants across requests. No permission query for pages without actionable rows. OWNER scoped to actual snapshot shop; ADMIN shop0 only. Actual redemption checks current grant in transaction. Preserve pagination/IDs/snapshots and original todo before cells. No unrelated services or data modified.

## Implementation
- [x] Add real integration SQL-count and ordinary/OWNER/ADMIN/revoke regressions, observe RED.
- [x] Add bounded request-local grant load in OrderLifecyclePersistence; pass to list views in OrderLifecycleService. GREEN targeted regression.
- [x] Full supported Java and frontend checks, real packaged browser, evidence; one whole-branch review and fixes if required.
**发布门禁：** 待办与证据随最终代码统一推送；最终提交三项 CI 全绿才交付，运行链接记录于交付和本地审计日志。
