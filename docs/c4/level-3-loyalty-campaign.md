# Rochallor Loyalty Platform — C4 Level 3 — Component — `loyalty-campaign`

| Field | Value |
|---|---|
| Version | 0.1 — Initial Draft |
| Status | DRAFT |
| Last updated | 2026-05-26 |
| Author | Nam Vu |
| Companion doc | [`docs/Digital-Loyalty-Arch.md`](../enterprise-architect.md) §11.3 |
| Preceding view | [`level-2-containers.md`](level-2-containers.md) |
| Sibling views | [`level-3-loyalty-core.md`](level-3-loyalty-core.md), [`level-3-loyalty-redemption.md`](level-3-loyalty-redemption.md) |
| Glossary | [`CONTEXT.md`](../../CONTEXT.md) |

---

## 1. Purpose & Scope

This document is the **C4 Level 3 — Component** view for the `loyalty-campaign` service. Its single job is to answer:

> **What components live inside `loyalty-campaign`, how do they author and schedule Drawings, and how are winners selected fairly and auditably?**

It zooms inside the single `loyalty-campaign` rectangle drawn at [L2 §3.1](level-2-containers.md#31-static-topology). `loyalty-campaign` owns two related concerns: **Campaigns** (marketing-driven, time-bounded earning multipliers / promos) and **Drawings** (sweepstakes — Members enter, a winner is drawn, the prize is fulfilled by reusing the existing Reward pipeline via T-13).

**In scope:**

- The application-level components inside `loyalty-campaign`.
- Campaign + Drawing aggregates, their lifecycle, and the Entry ledger.
- The Winner Selection algorithm and its audit trail.
- The internal touchpoint T-13: how a Drawing prize hands off to `loyalty-redemption` for fulfilment.

**Out of scope (deliberately):**

- How `loyalty-earning` applies a Campaign multiplier — that lives in the DSL Interpreter inside [`loyalty-earning`](level-3-loyalty-earning.md). `loyalty-campaign` only *exposes* active multipliers; it does not evaluate them.
- Reward fulfilment internals — Drawings reuse the same Cashback / Voucher pipeline owned by [`loyalty-redemption`](level-3-loyalty-redemption.md).

---

## 2. Reading the Diagrams

`loyalty-campaign` has three execution modes: **request-driven** (BEP authors Campaigns / Drawings, Mobile BFF lists active ones), **entry-driven** (`loyalty-redemption` records a Sweepstakes entry via the SweepstakesAdapter), and **scheduled** (Drawing Scheduler fires Winner Selection at the configured close time). We use **three sub-views**:

| Sub-view | Scope | What it answers |
|---|---|---|
| **§3.1 Static Topology** | All components + tables + structural relationships | *What lives inside `loyalty-campaign`?* |
| **§3.2 Entry & Authoring Path** | Member entry recording + BEP authoring | *How do entries land and how are Drawings configured?* |
| **§3.3 Winner Selection** | Scheduler fires → seeded RNG → winner Ledger entry → prize hand-off (T-13) | *How is the winner picked fairly, and how does the prize get paid out?* |

**Common legend** is identical to [`level-3-loyalty-core.md` §2](level-3-loyalty-core.md#2-reading-the-diagrams).

---

## 3. The Diagrams

### 3.1 Static Topology

<p align="center">
  <img src="../images/level-3-loyalty-campaign.svg" alt="">
</p>

```plantuml
@startuml level-3-loyalty-campaign
title C4 Level 3 — loyalty-campaign — Static Topology

skinparam shadowing false
skinparam defaultTextAlignment center
skinparam nodesep 28
skinparam ranksep 45

skinparam rectangle {
    roundCorner<<svc>>           20
    BorderStyle<<svc>>           dashed
    BackgroundColor<<svc>>       #f0f7ff
    BorderColor<<svc>>           #0b4884

    BackgroundColor<<component>> #0b4884
    FontColor<<component>>       #ffffff
    BorderColor<<component>>     #073661

    BackgroundColor<<sched>>     #00897b
    FontColor<<sched>>           #ffffff
    BorderColor<<sched>>         #00695c

    BackgroundColor<<data>>      #5d7a99
    FontColor<<data>>            #ffffff
    BorderColor<<data>>          #3d5470

    BackgroundColor<<ext>>       #b0bec5
    FontColor<<ext>>             #000000
    BorderColor<<ext>>           #78909c
}

skinparam package {
    BackgroundColor #fafafa
    BorderColor     #9e9e9e
    FontStyle       italic
}

' External neighbours
rectangle "loyalty-mobile-bff"  <<ext>> as mbff
rectangle "loyalty-admin-bff"   <<ext>> as abff
rectangle "loyalty-redemption"  <<ext>> as redm
rectangle "Shared MSK Kafka"    <<ext>> as msk

rectangle "Spring @Scheduled\n(in-pod, ShedLock-guarded)\npolls drawing.draw_at every 1 min" <<sched>> as cron

rectangle "**loyalty-campaign**" <<svc>> {

  package "Inbound" {
    rectangle "REST API Layer"          <<component>> as api
  }

  package "Campaign Context" {
    rectangle "Campaign Aggregate"      <<component>> as camp
  }

  package "Drawing Context" {
    rectangle "Drawing Aggregate"       <<component>> as draw
    rectangle "Entry Service"           <<component>> as entry
    rectangle "Drawing Scheduler"       <<component>> as sched
    rectangle "Winner Selection"        <<component>> as winner
  }

  package "Cross-cutting" {
    rectangle "Audit Log Writer"        <<component>> as audit
    rectangle "Outbox Relay"            <<component>> as relay
  }

  package "Data Tier (loyalty-campaign RDS)" {
    rectangle "campaign"            <<data>> as t_camp
    rectangle "drawing"             <<data>> as t_draw
    rectangle "drawing_entry"       <<data>> as t_entry
    rectangle "winner_record"       <<data>> as t_winner
    rectangle "campaign_audit_log"  <<data>> as t_audit
    rectangle "outbox"              <<data>> as t_outbox
  }
}

' Inbound
mbff --> api    : list active campaigns,\n list active drawings
abff --> api    : CRUD
redm --> api    : Drawing.recordEntry()  (T-13 entry)

' API → components
api  --> camp
api  --> draw
api  --> entry
api  --> audit

cron --> sched  : trigger at draw close time
sched --> winner : selectWinner(drawingId)
winner --> draw  : closeDrawing(winnerId)
winner --> relay : enqueueEvent(WinnerSelected)

' Table ownership
camp   --> t_camp
draw   --> t_draw
entry  --> t_entry
winner --> t_winner
audit  --> t_audit
relay  --> t_outbox

' Outbound
relay --> msk    : loyalty.campaign.*

' T-13 prize handoff (event-driven)
note bottom of msk
  loyalty-redemption listens for
  loyalty.campaign.WinnerSelected
  and, if the prize is a Reward,
  issues a system-initiated redemption
  via SweepstakesAdapter.
  This is the T-13 touchpoint.
end note

@enduml
```

### 3.2 Entry & Authoring Path

Two flows on the same surface. The hot flow is **entry recording** — a Member redeems an "Enter Drawing" Reward via Mobile BFF → `loyalty-redemption`'s SweepstakesAdapter → calls back into `loyalty-campaign`'s `Drawing.recordEntry`. The cool flow is BEP authoring.

<p align="center">
  <img src="../images/level-3-loyalty-campaign-entry.svg" alt="">
</p>

```plantuml
@startuml level-3-loyalty-campaign-entry
title C4 Level 3 — loyalty-campaign — Entry recording + authoring

actor "Campaign Manager"    as cm
participant "loyalty-admin-bff" as abff
participant "REST API Layer"    as api
participant "Campaign Aggregate" as camp
participant "Drawing Aggregate"  as draw
participant "Entry Service"      as entry
database  "campaign\ndrawing\ndrawing_entry" as db
participant "Audit Log Writer"   as audit

== Authoring: Campaign ==
cm   -> abff : Create Campaign\n{programId, name,\n starts_at, ends_at,\n multiplier_rule}
abff -> api  : POST /campaigns
api  -> camp : create(campaignDsl)
camp -> db   : INSERT campaign\n(status=DRAFT)
api  -> audit: log(CampaignCreated)

cm   -> abff : Activate
abff -> api  : PATCH /campaigns/{id}\n{status=ACTIVE}
api  -> camp : activate(id)
camp -> db   : UPDATE status=ACTIVE
api  -> audit: log(CampaignActivated)

== Authoring: Drawing ==
cm   -> abff : Create Drawing\n{programId, prize,\n entry_window, draw_at}
abff -> api  : POST /drawings
api  -> draw : create(drawingDsl)
draw -> db   : INSERT drawing\n(status=OPEN, draw_at)

== Entry recording (hot, called from SweepstakesAdapter) ==
note over abff, api
  This is the T-13 surface — called by
  loyalty-redemption.SweepstakesAdapter
  inside the Member's redemption Saga.
end note
participant "loyalty-redemption" as redm
redm -> api : POST /drawings/{id}/entries\n{memberId, sagaId,\n idempotencyKey}
api  -> entry : recordEntry(drawingId, memberId, idempKey)
entry -> db   : SELECT drawing\nWHERE id=:id AND status=OPEN\nAND :now BETWEEN starts_at AND ends_at
alt drawing open AND in window
    entry -> db : INSERT drawing_entry\n(drawingId, memberId, idempotencyKey)\nON CONFLICT (idempotencyKey) DO NOTHING
    entry --> api : entryId
    api  --> redm : 200 OK
else drawing closed
    entry --> api : RejectedReason
    api  --> redm : 409 Conflict
end

@enduml
```

**Notes on entries:**

- **Idempotency on `drawing_entry.idempotency_key`** — the SweepstakesAdapter's idempotency-key (derived from the Saga's idempotency-key) is unique per Drawing per Member per attempt. Replays of the same redemption don't enter a Member twice.
- **`ON CONFLICT DO NOTHING`** — the cheapest way to make entry recording idempotent at the DB level. We don't fail the Saga on a duplicate; we treat it as a successful no-op.
- **Window-gated** — a single conditional INSERT (`SELECT … WHERE status=OPEN AND :now BETWEEN starts_at AND ends_at`) eliminates the SELECT-then-INSERT race. Late entries are atomically rejected.

### 3.3 Winner Selection & Prize Hand-off (T-13)

When a Drawing reaches its `draw_at` time, the in-pod Drawing Scheduler (Spring `@Scheduled`, polling every 1 minute, ShedLock-guarded) fires Winner Selection. It picks **K = min(`winners_count`, N) winners without replacement** via the Drawing's `selection_strategy` — `SEEDED_RNG` (uniform), `WEIGHTED` (frozen per-entry weights), or `FIRST_N` (first K by arrival, no RNG). `SEEDED_RNG`/`WEIGHTED` are deterministic and seed-replayable; `FIRST_N` is arrival-deterministic. The K winners are recorded; one `DrawingCompleted` + K `WinnerSelected` events are published; `loyalty-redemption` picks them up and runs each prize through its standard Saga.

<p align="center">
  <img src="../images/level-3-loyalty-campaign-winner.svg" alt="">
</p>

```plantuml
@startuml level-3-loyalty-campaign-winner
title C4 Level 3 — loyalty-campaign — Winner Selection & T-13 prize hand-off

participant "Spring @Scheduled\n(every 1 min, ShedLock)" as cron
participant "Drawing Scheduler"     as sched
participant "Winner Selection"      as winner
database  "drawing\ndrawing_entry\nwinner_record" as db
participant "Outbox Relay"          as relay
queue     "loyalty.campaign.*"      as msk
participant "loyalty-redemption"    as redm

cron -> sched : trigger(drawingId, scheduled_at)
sched -> winner : selectWinner(drawingId)
activate winner

winner -> db : SELECT … FROM drawing\nWHERE id=:id AND status=OPEN\nFOR UPDATE
note right
  Pessimistic lock — only one
  pod may select a winner per
  drawing. Defensive against
  duplicated fires from drift / clock skew.
end note

winner -> db : SELECT count(*) FROM drawing_entry\nWHERE drawing_id=:id
note right
  N = total entries.
  If N=0 → no winner;
  Drawing closes as VOID.
end note

alt N > 0
    winner -> winner : K = min(winners_count, N)
    winner -> winner : seed = sha256(\n  drawingId || draw_at ||\n  HMAC_secret)
    note right
      SEEDED_RNG/WEIGHTED replay from
      seed + immutable entry order
      (+ frozen weights). FIRST_N uses
      entry_id order, no seed.
      HMAC secret rotated annually.
    end note
    winner -> winner : pick K distinct winners per\n selection_strategy (no replacement)
    winner -> db : SELECT entries\nFROM drawing_entry\nORDER BY entry_id\n(weight for WEIGHTED)
    winner -> db : INSERT K winner_record rows\n(drawingId, memberId,\n seed_hex, winner_index, drawn_at)
    winner -> db : UPDATE drawing SET status=CLOSED
    winner -> relay : enqueueEvent(DrawingCompleted{winnerMemberIds})\n + K x WinnerSelected{memberId, winnerIndex}
else N = 0
    winner -> db : UPDATE drawing SET status=VOID
    winner -> relay : enqueueEvent(DrawingVoid)
end
deactivate winner

== T-13 prize hand-off ==
relay -> msk : loyalty.campaign.WinnerSelected
msk -> redm : consume
note right of redm
  loyalty-redemption sees the
  WinnerSelected event, looks up
  the prize Reward, and runs a
  system-initiated redemption
  via SweepstakesAdapter →
  whichever underlying adapter
  the prize actually maps to
  (typically CashbackAdapter or
   BillPaymentVoucherAdapter).
end note

@enduml
```

**Why this design:**

- **Seeded-RNG, not "live randomness"** — for `SEEDED_RNG`/`WEIGHTED` the seed is derived deterministically from `(drawingId, draw_at, HMAC_secret)`. The HMAC secret is the only non-public input; with it — plus the immutable `entry_id` order and **frozen** per-entry weights — an auditor reproduces the selection. Without it, an attacker watching public state can't predict the winners. `FIRST_N` uses no seed: winners are the first K by `entry_id`, fair by arrival transparency.
- **K winners without replacement** — `K = min(winners_count, N)`; one `DrawingCompleted` summary + K `winner_record` rows / `WinnerSelected` events. `UNIQUE(drawing_id, winner_index)` blocks duplicate selection. `WEIGHTED` weights are frozen in `drawing_entry.weight` at entry time so a later tier change can't break replay.
- **Pessimistic lock on `drawing` during selection** — belt-and-braces against duplicated fires (ShedLock guarantees one Pod per tick at the application level; the `SELECT … FOR UPDATE` + `status=OPEN` predicate adds a second line of defence at the row level).
- **Prize hand-off via event, not direct call** — keeps `loyalty-campaign` decoupled from the Reward catalogue. `loyalty-redemption` is the only service that knows how to *fulfil* anything.
- **VOID outcome** — Drawings with zero entries close as VOID and emit a distinct event, so BEP can review and (if desired) re-run with extended dates.

---

## 4. Component Inventory

| # | Component | Bounded context | Writes | Reads | Triggered by |
|---|---|---|---|---|---|
| 1 | **REST API Layer** | (Cross-cutting) | — | — | HTTPS / mTLS from BFFs and `loyalty-redemption` |
| 2 | **Campaign Aggregate** | Campaign | `campaign` | `campaign` | API: Campaign CRUD + activation |
| 3 | **Drawing Aggregate** | Drawing | `drawing` | `drawing` | API: Drawing CRUD + activation; Winner Selection: close |
| 4 | **Entry Service** | Drawing | `drawing_entry` | `drawing`, `drawing_entry` | API: `Drawing.recordEntry` (T-13 inbound) |
| 5 | **Drawing Scheduler** | Drawing | — | `drawing` | Spring `@Scheduled` in-pod every 1 min, ShedLock-guarded; polls for Drawings with `status=OPEN AND draw_at <= now()` |
| 6 | **Winner Selection** | Drawing | `winner_record`, `drawing` (status close) | `drawing`, `drawing_entry` | Drawing Scheduler |
| 7 | **Audit Log Writer** | (Cross-cutting) | `campaign_audit_log` | — | Every admin write via API (interceptor) |
| 8 | **Outbox Relay** | (Cross-cutting) | `outbox` | `outbox` | Internal scheduler (1s tick) |

---

## 5. Loyalty-Owned Tables in `loyalty-campaign RDS`

| Table | Purpose | Notes |
|---|---|---|
| **`campaign`** | Per-Program Campaign definitions (multiplier rule, validity window). | Authored in BEP; status `DRAFT → ACTIVE → ARCHIVED`. |
| **`drawing`** | Per-Program Drawing (sweepstakes) definitions: prize, entry window, draw time. | Status `OPEN → CLOSED → VOID`. |
| **`drawing_entry`** | One row per Member entry per Drawing. | `idempotency_key` unique; `(drawing_id, member_id)` non-unique if Drawing allows multiple entries. |
| **`winner_record`** | One immutable row **per winner** (K per drawing): `{drawingId, memberId, seed_hex, winnerIndex, drawn_at}`. | Audit-replayable for `SEEDED_RNG`/`WEIGHTED` (row + HMAC secret + frozen entries/weights); `seed_hex` NULL for `FIRST_N`. |
| **`campaign_audit_log`** | Per-service audit trail for every BEP-originated Campaign / Drawing write. | ≥ 7-year retention. **Tamper-evident**: hash-chained + DB-immutable, nightly-sealed to S3 Object Lock WORM. |
| **`outbox`** | Transactional-outbox staging for `loyalty.campaign.*`. | Drained by Outbox Relay. |
| **`shedlock`** | ShedLock distributed-lock table for in-pod `@Scheduled` methods. | Required because `loyalty-campaign` runs multi-Pod. |

---

## 6. External Edges Re-exposed from L2

| Direction | Counterparty | Mechanism | Triggers which component |
|---|---|---|---|
| Sync inbound | `loyalty-mobile-bff` | REST/JSON via mTLS | REST API Layer → Campaign / Drawing list |
| Sync inbound | `loyalty-admin-bff` | REST/JSON via mTLS | REST API Layer → Campaign / Drawing CRUD |
| Sync inbound | `loyalty-redemption` | REST/JSON via mTLS | REST API Layer → Entry Service (T-13) |
| Async outbound | Shared MSK Kafka — `loyalty.campaign.*` | Kafka producer (Outbox Relay) | Outbox Relay |
| JDBC | `loyalty-campaign RDS` | JDBC (HikariCP) | All components owning a table |

---

## 7. Invariants & Cross-References

- **Winner selection** — K winners without replacement via `selection_strategy` (`SEEDED_RNG`/`WEIGHTED` are seed-replayable; `FIRST_N` is arrival-deterministic). HMAC secret rotation is owned by the platform SRE; `winner_record` retains the seed hex (NULL for `FIRST_N`) plus the frozen entry order/weights so any past selection can be verified.
- **`drawing_entry.idempotency_key` is unique** — Saga replays cannot enter a Member twice.
- **Window enforcement at the DB level** — entries are gated by a single conditional INSERT; no SELECT-then-INSERT race.
- **Prize fulfilment is a Reward, not a special path** — Drawings reuse the existing `loyalty-redemption` pipeline (T-13). This means a sweepstakes prize gets the same audit, the same Ledger entries, and the same fraud controls as a Member-initiated redemption.
- **No PII inside `loyalty-campaign`** — entries reference `memberId` only; display-name lookups happen at the BFF, never persisted.

Next L3 view: [`level-3-loyalty-integration-bridge.md`](level-3-loyalty-integration-bridge.md) — per-topic consumers, schema translation, velocity anomaly.

---

*End of document.*
