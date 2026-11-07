# Smart Life Stack Upgrade Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for inline implementation and one independent whole-branch review at completion.

**Goal:** Upgrade to JDK 21, Spring Boot 3 and Vue 3, preserve business behavior, demonstrate a running system, and publish a green commit.

**Architecture:** Keep the single Spring Boot application and multi-page frontend. Share a small Vue 3 bootstrap adapter across the existing pages and retain local, version-pinned browser bundles. Use a separate Docker Compose project for integration verification.

**Tech Stack:** JDK 21; Boot 3.5.16; MyBatis-Plus 3.5.18.1; Redisson 4.8.0; Hutool 5.8.48; RocketMQ client 5.5.1; Vue 3.5.45; Element Plus 2.14.8; Axios 1.21.0.

**Spec:** `docs/superpowers/specs/2026-11-06-stack-upgrade-design.md`

## Global Constraints

- Existing business rules, API routes, response envelope, authentication policy and SQL schema remain unchanged.
- No router, state library, service split or new user workflow.
- Third-party bundles keep their licenses; downloaded npm packages must pass integrity verification.
- Test services use a dedicated project and loopback ports; do not modify existing databases or containers.
- Publish only after local startup/API/browser checks, independent review and green remote CI.

## Review Focus

- Boot 3 Redis configuration must bind the same host, port, password, database and TLS setting for Lettuce and Redisson.
- MyBatis pagination/count API migration must retain result shapes and duplicate-query semantics.
- Jakarta migration must retain request/session/interceptor and multipart behavior.
- Vue 3 slots, data factories, footer navigation, tabs, rating display and validation feedback must remain usable.
- Real MQ consumption must still create the accepted order; framework startup alone is insufficient.

## Task 1: Backend compatibility migration

- [x] Confirm clean baseline unit tests on JDK 8 and record the starting commit.
- [x] Update `pom.xml`, Jakarta imports, Redis property layout/config adapter, and required MyBatis count types; run the suite to expose compatibility failures, then resolve only migration failures.
- [x] Add isolated test infrastructure plus an integration smoke test covering existing query, login, pagination, multipart, and Lua/MQ order behavior.
- [x] Run JDK 21 `mvn clean verify`, start the packaged application against the test services, and run the integration smoke test.
- [x] Record results in `docs/validation/stack-upgrade.md`.

## Task 2: Frontend compatibility migration

- [x] Pin and integrity-check the Vue, Element Plus, Axios bundles and license files.
- [x] Add migration checks for app creation/data conversion and preserve request/response boundaries.
- [x] Adapt bootstrap, footer registration, named slots, read-only rating bindings and tab events; preserve existing business methods.
- [x] Run static asset/script checks and verify actual browser rendering and representative interactions. Save screenshots in `docs/screenshots/`.
- [x] Record the behavior checks and any necessary compatibility decisions.

## Task 3: Delivery

- [x] Update README, configuration/runtime instructions, third-party notices and CI for JDK 21, frontend checks and integration smoke verification.
- [x] Obtain one independent review of the complete diff and address material migration findings.
- [x] Run final checks, verify business-rule diff boundaries, merge the verified upgrade branch and push to GitHub.
- [x] Verify all checks for the exact published commit are green. Deliver versions, running/demo commands, test results and documented limitations.
