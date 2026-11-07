# Stack upgrade validation

## Request and scope

- User: “JDK21…springboot3…其他逻辑先不修改…升级完成之后跑起来没问题就推送到github”.
- Follow-up: “前端也升级到 Vue 3，保持现有交互”.
- Starting commit: `fa290a1fbc4e296cbcade05fe0e105c71ee8d6c2`.
- Upgrade branch: `chore/upgrade-java21-boot3-vue3` in the existing clean checkout.
- Versions were checked against official documentation and Maven Central/npm metadata. Context7 tools were unavailable; no API was inferred from an unrelated framework version.

## Plan

The design and implementation plan are in `docs/superpowers/`. The user's explicit stack choices and authorization to complete/publish govern this work. Backend and frontend migrations are verified independently before combined runtime checks.

## Baseline and compatibility findings

- JDK 8 baseline: 5 unit tests passed with no failures/errors/skips.
- The first JDK 21 build exposed the moved MyBatis service package. The published library contains `com.baomidou.mybatisplus.spring.service` instead of the former `extension.service`; adapt imports without changing service method bodies.
- Ruling: use MyBatis-Plus `3.5.18.1`, the published corrective patch identified in the official release list. The initial metadata filter only accepted three numeric components and missed this patch.
- Ruling: RocketMQ publishes client `5.5.1`, while its official Docker registry currently publishes broker `5.5.0`. Use that server image with the classic `5.5.1` client, and verify actual message consumption before accepting compatibility. The initial `5.5.1` image pull failed because no such image tag exists.
- All three npm browser packages passed their registry SHA-512 integrity checks before writing bundle files.
- First packaged startup failed with `Public Key Retrieval is not allowed`: the former URL explicitly disabled TLS, while MySQL 8.4 uses caching SHA-2 authentication. Adopt the JDBC driver's configurable `sslMode=PREFERRED` default rather than enabling public-key retrieval; validate the real connection after rebuilding.
- First browser run showed unrendered Vue templates and `Vue.createApp is not a function`, while a direct HTTP download and Node evaluation confirmed that the server had Vue 3. Old browser-cached bundles must be invalidated as part of the migration; add versioned asset URLs and recheck the page.

## Backend and frontend verification (2026-11-07)

- JDK 21 `clean verify -Dtest=*Test,StackUpgradeIT`: 9 tests passed (5 unit + 4 integration), no failures/errors/skips. Real MySQL 8.4, Redis 8.4 and RocketMQ 5.5.0 were used in the dedicated `smart-life-upgrade` Compose project. The accepted order was consumed and persisted once, both inventory stores decremented, and the repeated claim was rejected.
- Packaged application started on port 8081 and logged successful MQ consumer startup. Configurable MySQL TLS resolved the authentication failure without enabling public-key retrieval.
- Redisson 4's global password API was verified against its published JAR. The updated configuration regression test first failed (expected password, actual null), then passed after switching to `Config.setPassword`.
- Dependency-tree inspection traced duplicate Commons Logging to RocketMQ → commons-validator; exclude that implementation while retaining Spring's logging bridge.
- Browser login validation, simulated code countdown, real login redirect, shop category dropdown and read-only ratings passed. Cache-busting URLs resolved the stale Vue 2 bundle failure.
- Browser testing then exposed remaining constant `v-model="4.5"` bindings in detail-page mock ratings. Adding the official, version-matched Vue compiler to the frontend check reproduced that error before the binding fix. Both pages now use read-only values. All 10 page templates compile; 17 scripts and 129 local references pass.
- Ruling: add `@vue/compiler-dom` as a development-only dependency, without changing browser delivery/build architecture. Plain JavaScript parsing missed the template errors; the compiler is pinned to the shipped Vue version.
- Ruling: preserve Vue 2's voucher filtering behavior by putting the loop on an outer `template`, because Vue 3 reverses `v-if`/`v-for` precedence. No change to expiry policy or API calls.
- One local rebuild encountered Windows' running-JAR file lock. Stop only this task's application process before rebuilding; no code change was required.
- Final packaged JAR started successfully on JDK 21 with the revised logging and Redisson configuration. Browser checks at desktop and 390px mobile width covered shop detail, profile tabs, footer navigation, the existing merchant selection overlay and login validation. With active/future/expired synthetic vouchers, the expired one stayed hidden and the future one displayed the original not-started message. No new console errors/warnings occurred after the template fixes.
- Screenshots: `docs/screenshots/login-vue3.png`, `shop-vue3.png`, `profile-vue3.png`. The shop screenshot includes synthetic fixture coupons; the profile uses a generated local test account. Existing empty demo shop images and static mock comments remain as before.
