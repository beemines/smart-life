# Local HTTP evidence, 2026-11-14

Source b66f2ad3ce12876d4ee55aed281f5503be17ff62; JAR SHA256 1e5411dade81dec0f61231ab693bdfbacec2ce7bb5d295871853020a4fb0c4af.

Supported clean verify v5 passed 193 tests, zero failures/errors/skips (8m54s). Earlier interrupted v2/v3 are not success evidence. v4 failed because Docker Desktop was stopped and Redis refused connections; existing named containers were started without recreation before v5.

Actual Apache JMeter 5.6.3 results: [complete raw evidence](results/2026-11-14-final-b66f2ad-complete/environment.json). Reproduce with scripts/load/GUIDE.md. Offline checks: five tests passed.

The committed measurements above used the earlier runner and remain unchanged. Subsequent review hardened failure gates for optimized Python, isolated the child JVM's environment/configuration, preserved last complete observations on drain failure, and covered PID-manifest write failure. The revised runner has 15 offline tests passing in both normal and `python -O` modes; these failure-path checks are not new performance measurements. Actual dependency hangs were not induced. Production Java and the measured JAR are unchanged.

| Profile | Round | Samples | RPS | p95 ms | System errors |
| --- | --- | --- | --- | --- | --- |
| hot | 1 | 21795 | 2226.5 | 4 | 0 |
| hot | 2 | 30815 | 3174.5 | 2 | 0 |
| hot | 3 | 35817 | 3659.3 | 2 | 0 |
| cold | 1 | 300 | 104.5 | 44 | 0 |
| cold | 2 | 300 | 99.0 | 46 | 0 |
| cold | 3 | 300 | 94.9 | 44 | 0 |
| orders | 1 | 100 | 24.3 | 3840 | 0 |
| orders | 2 | 100 | 28.5 | 3039 | 0 |
| orders | 3 | 100 | 29.2 | 2773 | 0 |

Each order round reconciled all 100 distinct user/request pairs: 10 accepted CREATED unpaid SQL orders, 90 expected sold-out business rejections, SQL/Redis stock zero, no pending/manual/return/lifecycle contradictions. Expected rejections count as valid contract samples, not effective orders. Full snapshots accompany each reconciliation.

Ten threads measured hot (10s) and cold (300 unique first-access shops) requests; orders used 100 distinct users and stock10. JVM, injector and dependencies share this Windows machine. Background periodic scans were disabled. These short fixed-concurrency measurements establish current-profile behavior, not production capacity, saturation, improvement or an SLO. Resource boundary observations and complete environment/hash metadata are in the JSON.

The first measurement attempt completed all three workload rounds but failed collecting localized cmd/ver metadata (UTF8 decoding). Its artifacts and fixtures remain private audit evidence under .superpowers; it is excluded from this report. OS collection now uses platform.platform. A fresh complete rerun passed, stopped its app and cleaned only recorded owned fixtures; its manifest records cleaned=true. Bloom bits and one run topic remain as documented. No private authentication CSV/token/application log is published.
