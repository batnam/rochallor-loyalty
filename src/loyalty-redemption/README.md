# loyalty-redemption

The **two-phase redemption Saga** — turns a Member's "Redeem" tap into committed points + a fulfilled
reward ([C4 L3 `loyalty-redemption`](../../docs/c4/level-3-loyalty-redemption.md)).

It exposes `POST /redemptions` (called by `loyalty-mobile-bff`): the Saga gates **Eligibility**,
reserves points in `loyalty-core`, dispatches a **Fulfillment Adapter** by Reward Type, then commits
(or releases on failure). Three adapters run **in-process** for the sub-second paths (Cashback,
Bill-Payment Voucher, Sweepstakes); the partner-dependent **3rd-Party Voucher** path returns
`FULFILLING` and is resumed asynchronously when the partner webhook lands
(`loyalty.fulfillment.resume.v1` → Resume Consumer). Outcomes publish on `loyalty.redemption.*`. It
co-locates the **Reward** and **Fulfillment** bounded contexts so the in-process dispatch stays cheap.

> **New here?** Read [`DETAILED-DESIGN.md`](DETAILED-DESIGN.md) — a self-contained design + user guide
> (the Saga state machine, the SPI, the sync/async flows, authoring + approval gate, data/config
> reference, run & operate).

## Invariants it upholds

- **Two-phase, with a separate Reservation.** The Saga state in `redemption_saga` mirrors but does not
  replace the Reservation row in `loyalty-core`. reserve → fulfil → commit / release.
- **Eligibility before reserve** — cheap rejects (tier / segment / currency / tenure / per-Member cap /
  balance) return `409` before any balance is held. The Engine is a **pure function**.
- **Compensation by `release()`, never by deleting** — a failed fulfilment releases the reservation;
  it never mutates Ledger rows (P5). Inventory is restored on the same path.
- **The Saga is the only writer of `redemption_saga`** — Adapters return outcomes; the Saga records
  them. Transitions go through an explicit state machine (terminal states reject further moves), so a
  duplicate partner webhook on a COMMITTED saga is a silent no-op.
- **Adapters are an SPI, not a kitchen-sink** — adding a Reward Type is bounded: implement
  `FulfillmentAdapter`, register the bean, add one `RewardType`. The Saga does not change.
- **Inventory as a conditional UPDATE** — `reward_inventory` is decremented by a single
  `UPDATE … WHERE remaining >= 1` (no SELECT-then-UPDATE race); "0 rows affected" = exhausted.
- **Idempotent submit** — `redemption_idempotency` maps the `Idempotency-Key` to its saga, so a replay
  returns the original outcome instead of running the Saga twice.
- **Transactional outbox** — `loyalty.redemption.{completed,failed}.v1` is staged in the Saga's
  transaction and relayed to MSK separately (at-least-once; dedup downstream by `eventId`).

## Slice status

| Slice | Status |
|---|---|
| Saga state machine (`RESERVED → FULFILLING → COMMITTED \| RELEASED \| FAILED`, pure) | ✅ scaffolded (TDD) |
| Eligibility Engine (pure: tier / segment / currency / tenure / per-Member cap / balance) | ✅ scaffolded (TDD) |
| Fulfillment SPI + Adapter Registry (dispatch by Reward Type) | ✅ scaffolded (TDD) |
| Reward Catalogue (CRUD, approval-gated ACTIVE/pointCost, atomic inventory) | ✅ scaffolded (TDD gate) |
| Ledger Client (reserve / commit / release / projection → core) | ✅ scaffolded |
| Redemption Orchestrator (eligibility → reserve → dispatch → commit/release; resume) | ✅ scaffolded (TDD) |
| Four Adapters (Cashback, Bill-Payment Voucher, 3rd-Party Voucher async, Sweepstakes) | ✅ scaffolded |
| Resume Consumer (`loyalty.fulfillment.resume.v1`) + Outbox Relay + Completed/Failed events | ✅ scaffolded |
| REST API (submit/poll, reward-types, reward CRUD + eligible + PATCH gate), hash-chained audit | ✅ scaffolded |

**Deferred from this scaffold:** real **S3** voucher-PDF rendering + pre-signed URLs (Bill-Payment emits
a synthetic voucher artifact); the 4-eyes `REWARD_CHANGE` workflow (delegated to
admin-bff — redemption only hardens the `confirm` seam with `bepApprovalRef`); Reservation **TTL
sweeping** (lives in `loyalty-core`); `stepUpToken` enforcement (accepted, forwarded, not validated
here); mTLS wiring (cluster infra). v1 core's projection exposes balance + tier only, so the
segment / currency / tenure / tier-ordinal eligibility gates are wired-and-tested but dormant until the
projection is enriched.

## Build & run

Stack: **Java 25** · **Spring Boot 4.x** · **Spring Data JPA + PostgreSQL** · **Flyway** · **ShedLock**
· **Spring Kafka** · **Gradle (Kotlin DSL)** — synced with `loyalty-core` / `loyalty-earning`.

```bash
./gradlew test     # fast unit suite (saga/eligibility/SPI/orchestrator) + Testcontainers IT (Postgres + Kafka + WireMock)
./gradlew bootRun  # needs Postgres ($DB_URL), Kafka ($KAFKA_BOOTSTRAP_SERVERS), and core ($CORE_BASE_URL)
```

> Requires a **JDK 25** toolchain. Flyway owns the schema; Hibernate runs `ddl-auto: validate`. The IT
> stubs core's Reservation API + Payment Hub + the voucher partner with WireMock, so no sibling service
> is needed to run it. 39 unit tests + 7 Testcontainers integration tests.
