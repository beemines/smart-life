# Capacity Spike Execution Plan

> Use superpowers:subagent-driven-development for the bounded experimental implementer and a scoped evidence review. This is a disposable spike authorized by the user's request to simulate; no product feature is being built.

**Goal:** obtain actual, reproducible capacity and interference observations before choosing architecture changes.
**Spec:** docs/superpowers/specs/2026-11-15-capacity-spike.md

- [x] Inspected existing SQL/schema and available JDK/JDBC/Docker; recorded ownership and bounded design before mutation.
- [x] Provisioned only new localhost isolated MySQL/Redis, with explicit caps and current indexes.
- [x] Loaded 100k then 1m orders. Independent review invalidated the old timestamp-bound SQL readings; valid measurements were repeated on the 1m dataset only, with exact returned-row assertions, concurrency sweeps, CPU and buffer controls. There is no valid 100k performance baseline or demonstrated growth curve.
- [x] Compared online SQL reads alone, with four batch staging workers and with one worker, retaining throughput/latency tradeoffs.
- [x] Compared actual HTTP reads with real 1000-row XLSX imports: one app, capped concurrency and two app processes sharing DB/Redis. Corrected per-thread persistent HTTP client; fixed-order/JIT/resource-budget limitations disclosed. No claim of implemented microservices or independent releases.
- [x] Verified 20 valid SQL profiles, 184625 successful queries returning 3078244 actual rows; 8 HTTP profiles, 51278 reads and 40 READY imports, zero errors. Owned services stopped, CPU/buffer restored, ports closed, data/audit retained.
- [x] Independent scoped review and correction rereview approved. Local report: docs/performance/2026-11-15-capacity-spike.md. Production source/frontend unchanged; no sharding/service implementation checkbox or GitHub push.
