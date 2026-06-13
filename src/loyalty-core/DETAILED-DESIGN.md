# `loyalty-core` — Detailed Design & User Guide

| Field | Value |
|---|---|
| Service | `loyalty-core` |
| Bounded contexts | Membership + Ledger (Shared Kernel) |
| Companion views | [C4 L3 `loyalty-core`](../../docs/c4/level-3-loyalty-core.md) · [C4 L2](../../docs/c4/level-2-containers.md) · [internal OpenAPI](../../docs/openapi/internal/loyalty-core.yaml) |
| Glossary | [`CONTEXT.md`](../../CONTEXT.md) |

---

## 0. How to read this document

Top to bottom is the intended order: what the service *is*, *why* it exists, the *vocabulary*, its
*runtime shape*, then each *flow* with a sequence diagram, then the *data / config* reference and the
*operate* guide. You should not need to read the code to understand the service.

## 1. What this service is, in one paragraph

`loyalty-core` is the system of record for a Member's points. It co-locates the **Membership** and
**Ledger** bounded contexts in one deployable so that a Point Ledger insert and all of its
consequences — the redeemable/qualifying balance update, FIFO cohort consumption, Tier
re-evaluation, any Reservation state change, and the outbound-event enqueue — happen inside a
**single Postgres transaction**. It exposes internal REST APIs (Ledger, Reservations, Projection,
Membership, Approval), consumes `loyalty.member.lifecycle.v1` from the integration bridge, runs two
in-pod scheduled jobs (Expiry, TTL Sweeper), and publishes `loyalty.ledger.*` / `loyalty.member.*`
through a transactional outbox.

## 2. Why it exists — the context

### 2.1 The problem it solves

Points are money-like. The platform's correctness rests on a handful of invariants
([`CONTEXT.md` §Invariants](../../CONTEXT.md)): the Ledger is append-only, every entry is
idempotency-keyed, and the denormalized balance is written by exactly one component in the same
transaction as the entry it reflects. Those guarantees are only cheap if Membership and Ledger share
a transaction boundary — otherwise they would need a distributed transaction or a saga over what is
conceptually one decision. So they are **one service**, not two (the "8 contexts → 7 services"
collapse, [C4 L2 §4.1](../../docs/c4/level-2-containers.md#41-application-containers-7)).

### 2.2 The decision that shaped it

Three patterns do the heavy lifting:

- **Append-only Ledger + idempotency** — corrections are compensating entries, never mutations;
  `(sourceRef, entryType)` uniqueness makes every write a safe replay.
- **Two-phase reservation** — a hold is a *mutable* row in `point_reservation`, deliberately **not**
  a Ledger entry (which would violate immutability for transient state). Only `commit` writes the
  permanent `Redeemed` entry.
- **Transactional outbox** — "the ledger insert and the event publish must both succeed" without a
  Postgres↔Kafka distributed transaction: stage the event in the same DB transaction, relay it later.

### 2.3 Where it sits

```
loyalty-earning ──earn()──▶ ┌───────────────┐ ──loyalty.ledger.*──▶ MSK ──▶ notification-service
loyalty-redemption ─reserve/commit/release─▶ │  loyalty-core │ ──loyalty.member.*─▶ MSK
mobile-bff / admin-bff ──read / admin──▶     └───────────────┘ ◀─loyalty.member.lifecycle.v1── bridge
                                                     │
                                              loyalty-core RDS (Postgres)
```

## 3. Vocabulary you need first

Defined fully in [`CONTEXT.md`](../../CONTEXT.md); the ones you must hold in your head here:

- **Member** — one per `(programId, customerId)`; owns balances, tier, lifecycle status. PII-free.
- **Point Ledger Entry** — one immutable row; carries `qualifying_delta`, `redeemable_delta`,
  `sourceRef`, `entryType` ∈ {`Earned`,`Redeemed`,`Expired`,`Reversed`,`Adjusted`}.
- **Redeemable / Qualifying Balance** — `SUM(redeemable_delta)` / windowed `SUM(qualifying_delta)`;
  denormalized onto `member`.
- **Reservation** — short-lived hold; `Effective Redeemable Balance = redeemable_balance − SUM(active HELD)`.
- **Point Cohort** — per-`Earned`-entry FIFO consumption tracker carrying `expires_at`.
- **Tier** — a *projection*, recomputed when Qualifying Balance moves; never an aggregate.

## 4. The runtime shape

### 4.1 Components (13)

Mirrors [L3 §4](../../docs/c4/level-3-loyalty-core.md#4-component-inventory). Each is one Spring bean
with one job and one table it is allowed to write:

| Component | Package | Writes |
|---|---|---|
| REST API Layer | `api` | — |
| Membership Aggregate | `member` | `member` (status) |
| Balance Projection | `projection` | `member` (balances) |
| Tier Projection | `projection` | `member` (tier) |
| Ledger Service (Write) | `ledger` | `point_ledger` |
| Reservation Manager | `reservation` | `point_reservation` |
| Cohort Projection | `cohort` | `point_cohort` |
| Approval Request Store | `approval` | `approval_request` |
| Audit Log Writer | `audit` | `core_audit_log` |
| Outbox Relay | `outbox` | `outbox` |
| Expiry Job + Processor | `job` | — (calls Ledger) |
| Reservation TTL Sweeper | `job` | — (calls Reservation Mgr) |
| Member-Lifecycle Consumer | `consume` | — (calls Membership) |

### 4.2 The defining property — one transaction

Every balance-affecting write loads the `Member` row `FOR UPDATE`, then within that one transaction:
insert the immutable Ledger entry → apply the balance delta → (if `qualifyingDelta ≠ 0`) recompute
the Tier → open/consume cohorts → enqueue the outbox row. The Outbox Relay drains in a *separate*
transaction. This is the whole reason Membership and Ledger are co-deployed.

## 5. The flows (each with a sequence diagram)

### 5.1 Earn — `POST /ledger/earn`

`loyalty-earning` calls this once per matching rule fire. Idempotent on `(sourceRef, Earned)`.

<p align="center">
  <img src="images/loyalty-core-flow-earn.svg" alt="Earn flow sequence diagram">
</p>

```plantuml
@startuml loyalty-core-flow-earn
participant earning as "loyalty-earning"
participant api as "LedgerController"
participant svc as "LedgerService"
participant bal as "BalanceProjection"
participant tier as "TierProjection"
participant coh as "CohortProjection"
participant ob as "OutboxRelay"
database db as "core RDS (1 txn)"

earning -> api : POST /ledger/earn {memberId, sourceRef, +q, +r}
api -> svc : appendEarn(...)
svc -> db : find (sourceRef, Earned) — replay? return original
svc -> db : Member FOR UPDATE
svc -> db : INSERT point_ledger (Earned, +q, +r)
svc -> bal : applyDelta(+r, +q)  -> UPDATE member balances
svc -> tier : recompute()        -> UPDATE member.current_tier_code
svc -> coh : open(cohort, expires_at = earnedAt + effectiveExpiryMonths)
svc -> ob : enqueue(loyalty.ledger.PointsEarned)  -> INSERT outbox
svc -> db : COMMIT
api --> earning : 201 (or 200 on replay)
@enduml
```

### 5.2 Redemption — reserve → commit / release

`loyalty-redemption`'s Saga. The reserve gate checks the **Effective** balance; the HELD hold does
**not** mutate `redeemable_balance` (per [`CONTEXT.md` "Reservation"](../../CONTEXT.md)). Only
`commit` writes the `Redeemed` entry and consumes cohorts FIFO.

<p align="center">
  <img src="images/loyalty-core-flow-redemption.svg" alt="Redemption reserve/commit/release sequence diagram">
</p>

```plantuml
@startuml loyalty-core-flow-redemption
participant redm as "loyalty-redemption"
participant rm as "ReservationManager"
participant svc as "LedgerService"
database db as "core RDS"

== reserve ==
redm -> rm : POST /reservations {points}  (Idempotency-Key)
rm -> db : effective = redeemable_balance − SUM(active HELD)
rm -> db : if effective < points -> 409 BALANCE_INSUFFICIENT
rm -> db : INSERT point_reservation (HELD, held_until = now + ttl)

== commit ==
redm -> rm : POST /reservations/{id}/commit {externalRef}
rm -> db : reservation FOR UPDATE; HELD? (COMMITTED -> idempotent return)
rm -> svc : appendRedeemed(points, sourceRef = reservation-{id})
svc -> db : INSERT Redeemed (0, −points) + UPDATE balance + consume cohorts FIFO
rm -> db : UPDATE reservation = COMMITTED, external_ref
rm -> db : enqueue loyalty.ledger.RedemptionCommitted

== release (failure / TTL) ==
redm -> rm : POST /reservations/{id}/release
rm -> db : UPDATE reservation = RELEASED  (no Ledger entry; balance never moved)
@enduml
```

### 5.3 Member lifecycle — `loyalty.member.lifecycle.v1` → close

The bridge republishes `customer.closed.v1` as this canonical event (keyed on `customerId`). v1 carries
one `lifecycleType`: `CUSTOMER_CLOSED`. The consumer closes **every** Member the Customer holds.

<p align="center">
  <img src="images/loyalty-core-flow-lifecycle.svg" alt="Member lifecycle close sequence diagram">
</p>

```plantuml
@startuml loyalty-core-flow-lifecycle
queue msk as "loyalty.member.lifecycle.v1"
participant c as "MemberLifecycleConsumer"
participant ma as "MembershipAggregate"
database db as "core RDS"
msk -> c : {customerId, lifecycleType=CUSTOMER_CLOSED}
c -> ma : closeForCustomer(customerId)
ma -> db : for each member -> UPDATE status = CLOSED + enqueue loyalty.member.MemberClosed
@enduml
```

### 5.4 Expiry (scheduled) — unconsumed cohorts → `Expired` entries

Nightly, ShedLock so one pod runs; each Program processed under a `pg_try_advisory_xact_lock(programId)`.
Each expired-but-unconsumed cohort yields one `Expired(−N,−N)` entry, idempotency-keyed by cohort id.

<p align="center">
  <img src="images/loyalty-core-flow-expiry.svg" alt="Nightly expiry sweep sequence diagram">
</p>

```plantuml
@startuml loyalty-core-flow-expiry
participant sched as "Scheduler\n(core.expiry.cron, nightly)"
participant job as "ExpiryJob"
participant proc as "ProgramExpiryProcessor"
participant svc as "LedgerService"
database db as "core RDS"

sched -> job : run()   [ShedLock "pointExpiry" — one pod/tick]
activate job
job -> db : SELECT program (all)
loop each Program
  job -> proc : expireProgram(programId, now)   [own txn]
  activate proc
  proc -> db : pg_try_advisory_xact_lock(programId)
  alt lock held elsewhere
    proc --> job : 0 (skip this Program)
  end
  proc -> db : SELECT cohort WHERE expires_at < now\n ORDER BY earned_at ASC
  loop each unconsumed expired cohort
    proc -> db : cohort.expire(remaining)
    proc -> svc : appendExpired(memberId, programId, remaining,\n sourceRef = "expiry-cohort-{id}")
    svc -> db : find (sourceRef, Expired) — replay? skip
    svc -> db : Member FOR UPDATE; INSERT Expired(-N,-N);\n UPDATE balance; recompute tier;\n enqueue loyalty.ledger.PointsExpired
  end
  deactivate proc
end
deactivate job
note right of svc
  One Expired entry per cohort, idempotency-keyed by
  cohort id. Same single-transaction shape as Earn (§5.1).
end note
@enduml
```

### 5.5 Reservation TTL sweep (scheduled) — stale HELD → RELEASED

Every 60s, ShedLock-guarded, batched (LIMIT 500). Reuses `ReservationManager.release`; a hold that
was committed/released in the race is skipped.

<p align="center">
  <img src="images/loyalty-core-flow-ttl-sweep.svg" alt="Reservation TTL sweep sequence diagram">
</p>

```plantuml
@startuml loyalty-core-flow-ttl-sweep
participant sched as "Scheduler\n(every 60s)"
participant sweep as "ReservationTtlSweeper"
participant rm as "ReservationManager"
database db as "core RDS"

sched -> sweep : sweep()   [ShedLock "reservationTtlSweeper" — one pod/tick]
activate sweep
sweep -> db : findExpiredHeld(now, LIMIT 500)
loop each stale HELD reservation
  sweep -> rm : release(reservationId, "TTL_EXPIRED")
  rm -> db : reservation FOR UPDATE
  alt still HELD
    rm -> db : UPDATE reservation = RELEASED\n (no Ledger entry; balance never moved)
  else committed / released in the race
    rm --> sweep : CoreException -> skip
  end
end
note right of sweep
  Batched at 500 to keep the txn bounded;
  any remaining stale holds are picked up next tick.
end note
deactivate sweep
@enduml
```

### 5.6 Manual adjustment — approval → `Adjusted` entry

Maker creates an `approval_request` (PENDING). The 4-eyes runs in **BEP's Approval Workflow**; core's
`confirm` requires a `bepApprovalRef` and is the only path that writes an `Adjusted` entry (linked back
via `point_ledger.approval_request_id`). Every step is recorded in the hash-chained `core_audit_log`.

<p align="center">
  <img src="images/loyalty-core-flow-adjustment.svg" alt="Manual adjustment approval sequence diagram">
</p>

```plantuml
@startuml loyalty-core-flow-adjustment
participant abff as "loyalty-admin-bff"
participant api as "AdminApprovalController"
participant store as "ApprovalRequestStore"
participant svc as "LedgerService"
participant audit as "AuditLogWriter"
database db as "core RDS"

== Request (Maker) ==
abff -> api : POST /approval-requests\n{memberId, programId, q, r, reason}  (X-Actor-Id)
api -> store : create(actor, ...)
activate store
store -> db : INSERT approval_request (PENDING)
store -> audit : record(ADJUSTMENT_REQUESTED, before=null, after=delta+reason)
audit -> db : append hash-chained core_audit_log row
deactivate store
api --> abff : 201 Created {status=PENDING}

note over abff, api : 4-eyes runs in BEP's Approval Workflow (outside core)

== Confirm (Checker — approval-gated) ==
abff -> api : POST /approval-requests/{id}/confirm\n{bepApprovalRef}
api -> store : confirm(actor, id, bepApprovalRef)
activate store
note right of store
  blank bepApprovalRef -> 400 BEP_REF_REQUIRED
  already APPLIED    -> idempotent return
  not PENDING        -> 409 APPROVAL_NOT_PENDING
end note
store -> svc : appendAdjusted(memberId, programId, q, r, reason,\n approvalRequestId, sourceRef = "approval-{id}")
svc -> db : find (sourceRef, Adjusted) — replay? return original
svc -> db : Member FOR UPDATE; INSERT Adjusted(q, r);\n UPDATE balance; recompute tier;\n enqueue loyalty.ledger.PointsAdjusted
svc --> store : ledgerEntryId
store -> db : approval_request.apply(bepApprovalRef, ledgerEntryId) -> APPLIED
store -> audit : record(ADJUSTMENT_APPLIED, before=PENDING,\n after=APPLIED + ledgerEntryId)
deactivate store
api --> abff : 200 OK {status=APPLIED, ledgerEntryId}

== Reject (alternative outcome) ==
abff -> api : POST /approval-requests/{id}/reject {bepApprovalRef}
api -> store : reject(...) -> REJECTED\n (audit ADJUSTMENT_REJECTED; no Ledger entry)
@enduml
```

## 6. Data reference

The 8 owned tables ([L3 §5](../../docs/c4/level-3-loyalty-core.md#5-loyalty-owned-tables-in-loyalty-core-rds)),
created by Flyway [`V1`](src/main/resources/db/migration/V1__baseline.sql): `member`, `point_ledger`
(append-only trigger + `UNIQUE(source_ref, entry_type)`), `point_reservation`, `point_cohort`,
`approval_request`, `core_audit_log` (insert-only trigger + hash chain), `outbox`, `shedlock`. Plus the
scaffolding `program` / `tier` config tables seeded by [`V2`](src/main/resources/db/migration/V2__seed_program.sql).

Outbound event envelopes (`event` package): `LedgerEvent` (`loyalty.ledger.*`), `MemberEvent`
(`loyalty.member.*`) — self-describing, keyed by `memberId`, `Instant` as ISO-8601, byte-compatible
with the bridge's Jackson-2 serialization.

## 7. Error handling, idempotency, tracing

- **Idempotency** — primary key is `(sourceRef, entryType)`; reservation reserve is idempotent on
  `Idempotency-Key`; commit/release/confirm are idempotent on the entity's terminal state.
- **Errors** — domain failures throw `CoreException(status, code, detail)` rendered as RFC-7807
  Problem (`code` ∈ `BALANCE_INSUFFICIENT`, `RESERVATION_NOT_HELD`, `MEMBER_NOT_FOUND`, …).
- **Negative balance** — explicitly allowed; the reserve gate is the only balance guard.

## 8. Configuration reference (`application.yml`)

| Key | Default | Meaning |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/loyalty_core` | core RDS |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | Shared MSK |
| `core.topics.member-lifecycle` | `loyalty.member.lifecycle.v1` | consumed (from bridge) |
| `core.topics.ledger-events` | `loyalty.ledger.v1` | produced |
| `core.topics.member-events` | `loyalty.member.v1` | produced |
| `core.reservation.default-ttl-seconds` | `900` | hold TTL (15 min) |
| `core.expiry.cron` | `0 30 2 * * *` | nightly expiry sweep |
| `core.outbox.relay-batch-size` | `100` | rows per relay tick |

## 9. Build & run

See [`README.md`](README.md). `./gradlew test` runs the end-to-end IT against Testcontainers Postgres
+ Kafka (Docker required; skipped otherwise). Requires a JDK 25 toolchain.

## 10. Operating it

- **Multi-pod scheduling** — Expiry and TTL Sweeper are ShedLock-guarded via the `shedlock` table; one
  pod runs each tick. Expiry additionally takes a per-Program advisory lock.
- **Outbox** — relayed every 1s; `SENT` rows accumulate (a TTL purge job is out of scope for v1).
- **Audit** — `core_audit_log` is hash-chained and DB-immutable; nightly WORM sealing to S3 Object
  Lock is the documented next step (not in this scaffold).

## 11. FAQ — the day-one questions

- **Why doesn't reserve decrement the balance?** A HELD reservation is a hold, not a spend — the
  glossary defines Effective balance as `balance − active holds`. Only `commit` spends. (The internal
  OpenAPI prose that says reserve "decrements the projection" is superseded by the glossary + L3 sequence.)
- **Where does Program/Tier config come from?** Authoritative owner is `loyalty-earning`; this scaffold
  seeds a local copy so core runs standalone. Sync mechanism is deferred.
- **Can balance go negative?** Yes — a clawback can drive it below zero; the Member can't redeem until
  it recovers, but the invariant `balance = Σ deltas` always holds.

## 12. Cross-references

- Component view: [C4 L3 `loyalty-core`](../../docs/c4/level-3-loyalty-core.md)
- Container view: [C4 L2](../../docs/c4/level-2-containers.md)
- Internal API: [`docs/openapi/internal/loyalty-core.yaml`](../../docs/openapi/internal/loyalty-core.yaml)
- Glossary & invariants: [`CONTEXT.md`](../../CONTEXT.md)
- Upstream producer of `loyalty.member.lifecycle.v1`: [`loyalty-integration-bridge`](../loyalty-integration-bridge/README.md)
