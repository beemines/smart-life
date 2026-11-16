# Capacity exploration, not an architecture migration

User request: simulate single-database pressure and conditions relevant to independent deployment/scaling. This is an authorized disposable measurement spike, not approval to introduce production sharding or microservices.

Use a newly named, localhost-only Docker MySQL 8.4 instance, separate from all existing development containers/data. Keep current indexes and derive the order-list SELECT from the source. Measure growing synthetic order/lifecycle data and increasing concurrency, then online reads with representative batch staging writes and bounded batch concurrency. Disclose configured CPU/memory/buffer limits, host resources, client overhead, warmup, durations, errors and repeat variation. Obtain query plans and server status/counters; never infer a DB bottleneck from latency alone.

Prefer the existing JDK21 and JDBC driver already in Maven cache; no app dependency installation needed. Experiments may demonstrate a saturation point or show no database bottleneck within bounds. Do not fabricate one by removing indexes, sleeping in queries or imposing unrealistic locks. Resource-constrained results are simulation, not evidence the user's real system already requires sharding. SQL-only measurements must not be described as full HTTP/MQ/business throughput.

Compare foreground-only, concurrent batch staging and capped batch staging under the same environment. These can establish contention and a reason to isolate/background-limit work; they cannot by themselves prove separate JVM deployment, independent release needs, or sharding benefit. If more evidence is needed, specify a next experiment rather than claim it exists.

Only owned new container/schema/files may mutate. Bound dataset size, concurrency, wall time and SQL/socket timeouts. Retain raw evidence and ownership manifest. Do not stop/edit existing containers, flush databases, push main or change production source. Stop the owned experimental container at finish; retain its volume/data for reproducibility unless user requests deletion.
