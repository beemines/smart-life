# P1 boundaries implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Inline implementation, TDD per task, one fresh whole-branch review at the end.

**Goal:** Repair the eight approved permission, eligibility, cache, logout, upload/deletion and publication defects; verify actual pages and all final CI before delivery.

**Architecture:** Extend existing services and pages. Add minimal SQL authority/ownership and durable sync records; keep Redis/Lua, Redisson and RocketMQ. No new dependencies or business pages.

**Tech Stack:** Existing JDK21/Boot3.5.16/MyBatis-Plus3.5.18.1/Redis8.4/Redisson4.8.0/RocketMQ5.5.1/Vue3.

**Spec:** docs/superpowers/specs/2026-11-11-p1-boundaries-design.md

## Global constraints

- Eight reviewed fixes only; new order/payment/refund features and one-user-one-voucher rule unchanged.
- Only dedicated smart-life-upgrade services and synthetic fixtures; no implicit real-user administrator promotion.
- PNG/JPEG5MiB, <=20million pixels, each dimension<=8192; explicitAsia/Shanghai activity zone, interval[begin,end).
- Preserve public reads and exact login-path/trailing-slash behavior; protect writes at login AND authority layer.
- Keep original before descriptions; only mark todo rows with verified evidence after code CI.

## Review focus

- Cache invalidation before commit/late fill, missing Redis/Bloom and two independently cached instances.
- Fresh versus previously published stock; failed ack/restart must never reset reserved stock.
- Same-order valid preallocation must survive current activity expiry/down state.
- Image signature/dimensions/path/ownership must all be checked before harmful file operations.
- Already-revoked logout is idempotent; old exact login slash route remains protected.

### Task1: write authority, owner uploads and logout

Files: LoginInterceptor/MvcConfig/public-read annotation, minimal authority helper and narrow shop/voucher DTOs; UploadController + focused file service; UserController/common.js/info/blog-edit. Add migration/fresh DDL for authority and uploaded ownership. Tests P1BoundaryIT, UploadControllerTest and actual JS page-handler tests.

Interfaces: authority.requireAdmin()/requireShopWrite(Long); uploaded.store(MultipartFile)/delete(String) use UserHolder; shop/voucher controllers sanitize DTOs then call existing modules. Logout revokes request header token and returns repeat-safe success.

- [x] RED: anonymous/cross-owner writes, real/fake/empty/wrong MIME/oversize images, owner DELETE versus GET/path traversal, revoked/repeat logout; update existing intentionally anonymous fixture assumptions only after observing RED.
- [x] Implement minimal permission checks and owner metadata, image validation/re-encoding, idempotent deletion, logout; keep public reads.
- [x] RED actual blog-edit stores原始name, DELETE uses originalname, published images retain display URLs; info clears login cache; implement and GREEN.
- [x] Focused Java/HTTP/JS GREEN; commit.

### Task2: shop visibility and coherent cache misses

Files: ShopServiceImpl, focused ShopCache/ShopCacheSync helper, Spring retry/MQ broadcasting; SQLsync version and migration. Tests ShopCacheIT with actual commit barrier and two independent contexts.

Interfaces: authority fromTask1; shop.create/update use per-shop lock + SQL transaction through TransactionTemplate. cache.find(id,load) locks miss/read/fill, bypasses caches while pending; sync.trySync(id) and retryPending(limit) evict then publish SEND_OK and conditional ack; receiver only evicts local cache.

- [x] RED: create immediately queryable, failed Bloom sync still queryable and recoverable, paused commit overlap and final local fill protected, existing target check.
- [x] Implement durable version, postcommit locked sync, pending-aware misses and retry; no cache fill outside lock.
- [x] RED/GREEN: two contexts with distinct local caches receive actual MQ broadcast; duplicate event safe; expired local entry plus missed notification falls back safely. Record refill count/latency separately from sync metadata queries if running benchmark.
- [x] Focused suite GREEN; commit.

### Task3: activity eligibility and durable first publication

Files: VoucherServiceImpl/DTO/status endpoint, VoucherEligibility/Publication helper, Reservation/seckill.lua/Persistence/enums; publication table/migration. Tests P1VoucherIT and existing order/Lua fixtures.

Interfaces: authorityTask1 uses persisted shop ID for status change; publication record in voucher SQL transaction with version/state/may_seed_stock, retry scheduler bounded; lock name shared by mutate/publish/new reserve. Eligibility supplies exists/ready/up and epoch bounds to Lua; same owner/terminal checked first. Default business zoneAsia/Shanghai.

- [ ] RED: future/end/down/unknown activity no order/preallocation, begin inclusive/end exclusive; owned PREPARING/PENDING replay and late consumption stay valid.
- [ ] RED: SQLcommit failure publishes nothing, Redis failure leaves durable pending, retry/real process restart never resets stock; legacy migration cannot create missing stock.
- [ ] Implement transactional publication intent, aftercommit/retry, readiness and Lua clock/rules; explicit status change shares lock and version fencing.
- [ ] Update original order safety/crash tests with valid synthetic activity fixtures; full backend GREEN; commit.

### Task4: real pages, independent review and delivery

Files: docs/screenshots/p1-boundaries, operations/migration/validation, CI and todolist.

- [ ] Full clean verify including all original integration and new P1 suites, actual JS and frontend checks, Nginx config/static checks.
- [ ] Packaged app + actual Nginx/browser: upload display/delete, fake file rejection, logout/login protection, activity refusal and valid existing order; save and inspect screenshots.
- [ ] One fresh whole-branch reviewer; accepted important findings get RED→GREEN and full rerun, no endless re-review loop.
- [ ] Push approved main update, inspect every job for exact SHA; fix failures. Only then check covered todo rows and final-doc CI; record finish.
