# In-store checkout implementation plan

> **For agentic workers:** Use superpowers:executing-plans, with independent calculator/frontend tasks delegated under dispatching-parallel-agents. Track steps with checkboxes.

**Goal:** Deliver the approved in-store bill, dense explicit promotion rules, mock settlement and full reversal, retaining legacy voucher redemption.
**Architecture:** Dedicated pure calculator plus current-monolith JDBC transactions. Extend existing shop detail and personal-page dialogs, not independent pages. No new framework or real money.
**Tech Stack:** Existing Java21/Boot3.5/MySQL8.4/Redis/MQ/Vue3.
**Spec:** docs/superpowers/specs/2026-11-15-in-store-checkout-design.md (user approved 2026-11-15).

## Global constraints
- Integer cents total1..100000000; discount basis points1..9999; total-N is eligible; discount rounds down.
- Merchant promotion→explicitly selected paid voucher→platform coupon. One of each. Free choices bounded20 each, stable automatic best; zero savings never consumes a coupon.
- Historical voucher checkout fields null: legacy redemption only. Opt-in new vouchers require bill, snapshot rules; no mutation of historic prices.
- Mock payments/full bill reversal only; no partial refund, cart, new services or sharding in this plan.
- SQL locks, DB UTC time, immutable successful settlement snapshot; quote120s, confirmed bill900s.
- Frontend real desktop/mobile and screenshots; local suites then final HEAD CI green before completion.

## Review focus
1. Existing direct redemption must not bypass new voucher minimum spend (Task2 regression).
2. A stale response must not permit old quote or another user's bill access (Task2/3).
3. A voucher restored by bill reversal may later be reused/refunded; repeating the old payment/reversal cannot mutate it (Task2).
4. No eligible amount or sub-cent discount cannot consume an entitlement (Task1/2).
5. Version changes/expiry during row-lock waits must invalidate confirmation, not silently change money (Task2).

## Fixed interfaces

Calculator `com.bee.smartlife.checkout.CheckoutCalculator` defines nested records:
- `Rule(long id,String kind,long threshold,long reduction,int payableBps,long cap,int priority,java.time.Instant expiresAt,boolean stackMerchant,boolean stackVoucher,boolean stackPlatform)`; kind FULL_REDUCTION or DISCOUNT; cap0 means unlimited.
- `PaidVoucher(long id,long price,long face,long threshold,boolean stackMerchant,boolean stackPlatform)`.
- `Calculation(long total,long excluded,long eligible,long merchantDiscount,long voucherDeduction,long unusedVoucherValue,long platformDiscount,long payable,long previousPaid,long totalCash,long cashSaving,Long merchantId,Long voucherId,Long platformId)`.
- `static Calculation calculate(long total,long excluded,Rule merchant,PaidVoucher voucher,Rule platform)` throws IllegalArgumentException for invalid rules/combination/zero benefit.
- `static Calculation best(long total,long excluded,List<Rule> merchants,PaidVoucher voucher,List<Rule> platforms)` excludes invalid combos, chooses smallest payable then merchant priority descending, platform priority descending, platform expiry ascending, IDs ascending (none last).

HTTP uses existing Result `{success,data,errorMsg}` and non-2xx ApiException on errors. All IDs serialized as strings; monetary values numeric cents. Base `/checkout`:
- POST `/bills` body `{shopId,total,excluded,requestKey}` → bill view. Own draft creation idempotent/key-payload bound.
- GET `/bills?limit=10&beforeTime=&beforeId=` → `{items,nextTime,nextId,hasMore}` current user's bills.
- GET `/bills/{id}` own view; GET `/merchant/bills/{id}` authorized merchant view.
- POST `/merchant/bills/{id}/confirm` `{total,excluded}` → confirmed bill view.
- POST `/bills/{id}/quote` `{voucherId:null,automatic:true,merchantId:null,platformId:null}` → bill view with `quote:{token,expiresAt,calculation,...}`.
- POST `/bills/{id}/pay` `{token}`; POST `/bills/{id}/cancel` `{}`; POST `/merchant/bills/{id}/reverse` `{reason}` → bill view.
- GET `/bills/{id}/options` → `{vouchers,merchants,platforms}`. Candidate DTOs include string id/title, snapshot rules, eligible/disabled reason where appropriate. Vouchers id is paid ORDER id, not template id.
- GET `/rules?shopId=` authenticated rule listing (merchant and platform templates); POST `/rules` rule write body `{id?,shopId,title,scope,kind,threshold,reduction,payableBps,cap,priority,stackMerchant,stackVoucher,stackPlatform,beginTime,endTime,active}`. Owner only MERCHANT; admin PLATFORM. ISO input UTC with explicit Z.
- POST `/coupons` `{ruleId,userId,requestKey}` admin grant, idempotent/snapshot; POST `/coupons/{id}/revoke` admin, unused only.
- GET `/capabilities` → `{merchant,admin}`.
Bill view `{id,shopId,shopName,ownerUserId,state,total,excluded,amountVersion,createdAt,payDeadline,quote,settlement,canConfirm,canQuote,canPay,canCancel,canReverse}`. `settlement` includes calculation and successful token/selected IDs; after reversal still retained. Fresh detail computes action flags and lazy expiry. Options don't treat cached prices as authoritative.
Paid voucher creation DTO existing fields extended with nullable `checkoutEnabled,checkoutThreshold,checkoutStackMerchant,checkoutStackPlatform`, defaults legacy; lifecycle captures these. Root owns these Java/schema changes. Frontend agent owns opt-in creation controls if existing creation UI exists, otherwise expose rules via documented API and show eligibility info (do not invent a separate page).

## Task1: Pure calculation
- [x] Add `src/test/java/com/bee/smartlife/checkout/CheckoutCalculatorTest.java`, run expected compilation RED.
- [x] Implement `src/main/java/com/bee/smartlife/checkout/CheckoutCalculator.java` and test spec examples 18000/2000/8000/10000, merchant full reduction, 101 cents rounding, platform minimum1, invalid negative/overflow, zero benefit, stack compatibility, deterministic best.
- [x] Run targeted Maven tests green; return only owned files. Commit with integrated task boundary.

## Task2: Authoritative bill transaction and rule snapshots
Files: `checkout/CheckoutService.java`, `checkout/CheckoutController.java`, `checkout/CheckoutRequests.java`, migration `2026-11-15-checkout.sql`, initial DDL, existing Voucher/VoucherWrite/lifecycle capture/redeem; tests `CheckoutIT.java`.
- [x] Write integration tests against desired HTTP/service contract; observe RED (404/missing APIs).
- [x] Add idempotent additive migration and new-install schema, immutable snapshots, SQL state/locking and quote lifecycle.
- [x] Implement authenticated controller, user/merchant permissions, rules and platform grants, paged own bills, options/quotes/payment/reversal.
- [x] Prove owner isolation, grant cap, legacy behavior, quote replacement/version/expiry, creation key reuse, two bills same entitlement, old direct redeem vs bill, rollback before commit, response-loss replay, reversal+reuse and partial-refund refusal.
- [x] Run targeted real integration tests green; update CI integration list and migration instructions. Commit.

## Task3: Existing-page integration
Files: new `frontend/js/checkout.js`, extend `frontend/js/order-lifecycle.js`, `frontend/shop-detail.html`, `frontend/info.html`, relevant CSS, `scripts/checkout-client.test.mjs`, package.json.
- [x] Write client tests first: stale quote discard, no frontend price authority, error no fake success, duplicate pay busy, ID strings and money validation.
- [x] Extend current order panel with bill list, merchant bill lookup/confirm/reverse and rule controls; shop detail opens bill creation dialog, no independent page.
- [x] Quote includes full breakdown, previous paid, current payment, discarded face value. Dropdowns/options including automatic free best, deliberate voucher selection.
- [x] Run Node tests/template checks green. Root runs real UI and screenshots after backend available.

## Task4: Verify, review and deliver
- [x] Run existing and new Java unit/integration and frontend suites, real UI desktop/mobile, fresh/old migrations; save validation report/screenshots.
- [x] Independent fresh code review of full change, fix critical/important with reproductions and suite.
- [x] Mark only implemented and verified todo scope. Preserve original before column and deferred architecture/partial-refund items.
发布门槛：按用户授权提交推送main并监测最终HEAD全部CI；未通过时不得结束任务。结果由GitHub当前提交检查和交付回复记录，不在CI运行前预报成功。

Plan review: interfaces consistent; four tasks cover approved design; architecture/worker/cache experiments remain separate subsequent projects, not implicitly completed by checkout.

## 验证记录

用户确认后连续执行，无新增架构范围。67单元/168集成/60前端通过，实际桌面及390px流程、SQL最终账/券/库存核对完成。独立审查三项P2均RED→GREEN；字段类型边界和真实页面ID契约错误已修复。完整证据见`docs/validation/checkout.md`。
