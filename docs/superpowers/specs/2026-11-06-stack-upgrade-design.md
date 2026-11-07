# Smart Life stack upgrade

The user requested JDK 21, Spring Boot 3, related dependency upgrades, and then confirmed Vue 3 with existing interactions preserved. Running the upgraded application successfully is required before publishing to GitHub.

## Scope

- Backend: JDK 21, Spring Boot 3.5.16, MyBatis-Plus 3.5.18.1 Boot 3 starter, Redisson 4.8.0, Hutool 5.8.48, RocketMQ client 5.5.1. Let the Boot BOM manage its Redis client, JDBC driver, Jackson, Caffeine and Lombok versions.
- Frontend: Vue 3.5.45, Element Plus 2.14.8, Axios 1.21.0. Keep the existing HTML pages and local browser bundles. Adapt application creation, slots, read-only bindings and component events; preserve routes, request/response contracts and user operations.
- Runtime verification: isolated MySQL 8.4, Redis 8.4 and RocketMQ services; JDK 21 executable application, Nginx static resources and API proxy. Existing personal application services/data are outside scope.
- Verify browser interactions and save screenshots; update docs and CI to test the new stack.

## Explicit boundaries

Do not change order IDs, inventory rules, message acknowledgement/compensation policy, coupon lifecycle, authentication permissions, SQL schema or business endpoints. Required namespace/type/configuration changes are migration work, not business redesign.

## Approach

Use a local upgrade branch in the existing clean checkout. Migrate backend and frontend separately, verify them together against disposable development data, obtain an independent final review, then merge and push when verification passes. The user has already authorized this execution and conditional publication.

Versions are verified against official Spring/MyBatis documentation and Maven Central/npm registry metadata. Context7 is not available in this session; official versioned documentation is the fallback.
