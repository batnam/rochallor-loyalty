# loyalty-core

The **Membership + Ledger shared kernel** — the hot critical path of the Loyalty platform
([C4 L3 `loyalty-core`](../../docs/c4/level-3-loyalty-core.md)).

It co-locates two bounded contexts in one JVM / one Postgres transaction so that a Ledger insert and
everything it implies — balance projection, FIFO cohort consumption, Tier re-evaluation, Reservation
transition, and the outbox enqueue — **commit or roll back together**. It is the single source of
truth for a Member's points; `loyalty-earning` writes earn entries to it, `loyalty-redemption`
reserves against it, both BFFs read balance/tier/ledger from it, and it consumes the
`loyalty.member.lifecycle.v1` events `loyalty-integration-bridge` produces.

> **New here?** Read [`DETAILED-DESIGN.md`](DETAILED-DESIGN.md) — a self-contained design + user
> guide (concepts, the commit write-path, per-flow walkthroughs, data/config reference, run &
> operate). You should not need to read the code to understand the service.

## Invariants it upholds

- **Append-only Ledger** — `point_ledger` is never UPDATEd/DELETEd (`@Immutable` entity **and** a
  Postgres trigger that rejects mutation).
- **Idempotency** — `(sourceRef, entryType)` is unique; replays are silent no-ops.
- **Single writer per table / per column** — exactly one component mutates each table (the L3
  "Writes" column); balance/tier/lifecycle columns of `member` have distinct writers.
- **Two-phase redemption** — `reserve → commit | release`; reservations are mutable rows, never
  Ledger entries; only `commit` writes a `Redeemed` entry.
- **Transactional outbox** — `loyalty.ledger.*` / `loyalty.member.*` events are staged in the same
  transaction as the business write and relayed to MSK separately (at-least-once; dedup by `eventId`).

## Slice status

| Slice | Status |
|---|---|
| Ledger earn write path (`POST /ledger/earn`) → balance + cohort + tier + outbox | ✅ scaffolded |
| Two-phase reservation (`POST /reservations`, `/commit`, `/release`) + FIFO cohort consume | ✅ scaffolded |
| Member projection read (`GET /members/{id}/programs/{pid}/projection`) | ✅ scaffolded |
| Membership opt-in / opt-out + Member-Lifecycle Consumer (`loyalty.member.lifecycle.v1` → close) | ✅ scaffolded |
| Tier Projection (recompute on `qualifyingDelta ≠ 0`) | ✅ scaffolded |
| Outbox Relay (`@Scheduled` drain → MSK) | ✅ scaffolded |
| Expiry Job (nightly, ShedLock + per-Program advisory lock) | ✅ scaffolded |
| Reservation TTL Sweeper (60s, ShedLock) | ✅ scaffolded |
| Approval Request Store + Adjusted entry (4-eyes delegated to BEP) + hash-chained Audit Log | ✅ scaffolded |

**Deferred from this scaffold:** Program/Tier config ownership in `loyalty-earning` (core uses a
seeded local copy — see [`V2`](src/main/resources/db/migration/V2__seed_program.sql)); Qualifying
Balance windowing for `ROLLING_12_MONTHS`/`CALENDAR_YEAR`; S3 WORM sealing of the audit archive;
mTLS wiring (left to cluster infra).

## Build & run

Stack: **Java 25** · **Spring Boot 4.x** · **Spring Data JPA + PostgreSQL** · **Flyway** · **ShedLock**
· **Spring Kafka** · **Gradle (Kotlin DSL)** — synced with `loyalty-integration-bridge`.

```bash
./gradlew test          # end-to-end IT against Testcontainers Postgres + Kafka (skipped if Docker absent)
./gradlew bootRun       # needs Postgres ($DB_URL) + a Kafka broker ($KAFKA_BOOTSTRAP_SERVERS)
```

> Requires a **JDK 25** toolchain (the build pins `languageVersion = 25`). Flyway owns the schema;
> Hibernate runs `ddl-auto: validate`.
