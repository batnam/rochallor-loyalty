# Rochallor Loyalty Platform — C4 Level 3 — Component — `loyalty-redemption`

| Field | Value |
|---|---|
| Version | 0.1 — Initial Draft |
| Status | DRAFT |
| Last updated | 2026-05-26 |
| Author | Nam Vu |
| Companion doc | [`docs/Digital-Loyalty-Arch.md`](../enterprise-architect.md) §11.3 |
| Preceding view | [`level-2-containers.md`](level-2-containers.md) |
| Sibling views | [`level-3-loyalty-core.md`](level-3-loyalty-core.md), [`level-3-loyalty-earning.md`](level-3-loyalty-earning.md) |
| Glossary | [`CONTEXT.md`](../../CONTEXT.md) |

---

## 1. Purpose & Scope

This document is the **C4 Level 3 — Component** view for the `loyalty-redemption` service. Its single job is to answer:

> **What components live inside `loyalty-redemption`, how does the two-phase redemption Saga coordinate Reservation + Fulfillment + Commit, and what is the contract every Fulfillment Adapter must satisfy?**

It zooms inside the single `loyalty-redemption` rectangle drawn at [L2 §3.1](level-2-containers.md#31-static-topology). `loyalty-redemption` co-locates two bounded contexts — **Reward** and **Fulfillment** — in one deployable so that the Redemption Saga can call Adapters **in-process** for the sub-second paths (Cashback, Internal Voucher, Sweepstakes) and *only* hand off async for the partner-dependent path (3rd-Party Voucher).

**In scope:**

- The application-level components inside `loyalty-redemption`.
- The four shipped Fulfillment Adapters: **Cashback**, **Bill-Payment Voucher**, **3rd-Party Voucher**, **Sweepstakes**.
- The Adapter framework / SPI that makes adding a fifth adapter a non-event.
- Saga state machine: `RESERVED` → `FULFILLING` → `COMMITTED` | `RELEASED` | `FAILED`.
- The 3rd-Party Voucher async completion path (partner webhook → Bridge → resume Saga).

**Out of scope (deliberately):**

- Ledger semantics inside `reserve()` / `commit()` / `release()` — those are L3 of `loyalty-core`.
- The Bridge's translation of the partner webhook into the canonical resume event — that is L3 of `loyalty-integration-bridge`.
- Wire-format detail of partner / Payment Hub APIs.

---

## 2. Reading the Diagrams

`loyalty-redemption` has three execution modes: **request-driven** (Member submits a redemption via Mobile BFF), **async-resume** (3rd-party voucher partner reports completion), and **scheduled** (Reservation TTL Sweeper — but that one lives inside `loyalty-core`, see [`level-3-loyalty-core.md`](level-3-loyalty-core.md#33-scheduled--async-paths)). We use **three sub-views**:

| Sub-view | Scope | What it answers |
|---|---|---|
| **§3.1 Static Topology** | All components + tables + structural relationships + the four Adapters | *What lives inside `loyalty-redemption` and how is the Adapter framework wired?* |
| **§3.2 Sync Redemption Path** | The Saga from `submitRedemption()` → `reserve()` → adapter → `commit()` | *How does the in-process Saga keep Points and Fulfillment consistent?* |
| **§3.3 3rd-Party Voucher Async Path** | The break in the Saga: partner asynchronously confirms; Saga resumes | *How does the Saga survive a slow / unreliable partner?* |

**Common legend** is identical to [`level-3-loyalty-core.md` §2](level-3-loyalty-core.md#2-reading-the-diagrams). Conventions specific to this service:

- A **green box** marks a **Fulfillment Adapter** — pluggable component implementing the `FulfillmentAdapter` SPI. The Saga Orchestrator dispatches to one by Reward Type.
- The Saga is **in-process for 3 of 4 adapters**; the 3rd-Party Voucher adapter releases the request thread after submitting to the partner and the Saga resumes when the webhook lands.

---

## 3. The Diagrams

### 3.1 Static Topology

<p align="center">
  <img src="../images/level-3-loyalty-redemption.svg" alt="">
</p>

```plantuml
@startuml level-3-loyalty-redemption
title C4 Level 3 — loyalty-redemption — Static Topology

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

    BackgroundColor<<adapter>>   #00897b
    FontColor<<adapter>>         #ffffff
    BorderColor<<adapter>>       #00695c

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
rectangle "loyalty-core"        <<ext>> as core
rectangle "loyalty-campaign"    <<ext>> as camp
rectangle "loyalty-integration-bridge" <<ext>> as bridge
rectangle "Payment Hub"         <<ext>> as payhub
rectangle "3rd-Party Voucher\nProvider" <<ext>> as voucher
rectangle "S3 (voucher PDFs)"   <<ext>> as s3
rectangle "Shared MSK Kafka"    <<ext>> as msk

rectangle "**loyalty-redemption**" <<svc>> {

  package "Inbound" {
    rectangle "REST API Layer"            <<component>> as api
    rectangle "Resume Consumer"           <<component>> as resume_c
  }

  package "Reward Context" {
    rectangle "Reward Catalogue"          <<component>> as catalog
    rectangle "Eligibility Engine"        <<component>> as elig
  }

  package "Fulfillment Context" {
    rectangle "Redemption Orchestrator\n(Saga)"     <<component>> as saga
    rectangle "Adapter Framework / SPI"   <<component>> as spi

    rectangle "CashbackAdapter"           <<adapter>>  as a_cash
    rectangle "BillPaymentVoucherAdapter" <<adapter>>  as a_bill
    rectangle "ThirdPartyVoucherAdapter"  <<adapter>>  as a_3p
    rectangle "SweepstakesAdapter"        <<adapter>>  as a_swp
  }

  package "Cross-cutting" {
    rectangle "Ledger Client"             <<component>> as ledger
    rectangle "Audit Log Writer"          <<component>> as audit
    rectangle "Outbox Relay"              <<component>> as relay
  }

  package "Data Tier (loyalty-redemption RDS)" {
    rectangle "reward"               <<data>> as t_reward
    rectangle "reward_inventory"     <<data>> as t_inv
    rectangle "reward_eligibility"   <<data>> as t_elig
    rectangle "redemption_saga"      <<data>> as t_saga
    rectangle "redemption_audit_log" <<data>> as t_audit
    rectangle "outbox"               <<data>> as t_outbox
  }
}

' Inbound
mbff --> api      : browse / submit
abff --> api      : Reward CRUD
bridge --> resume_c : loyalty.fulfillment.resume.v1

' API → components
api --> catalog
api --> saga      : submitRedemption()
api --> audit

resume_c --> saga : resumeSaga(externalRef, outcome)

' Saga internals
saga --> elig    : check(member, reward)
saga --> ledger  : reserve / commit / release
saga --> spi     : dispatch(rewardType)
spi  --> a_cash
spi  --> a_bill
spi  --> a_3p
spi  --> a_swp
saga --> relay

' Adapter outbound calls
a_cash --> payhub  : disburse(amount, CASA)
a_bill --> s3      : PDF render + put
a_3p   --> voucher : provision(SKU)
a_swp  --> camp    : Drawing.recordEntry

' Table ownership
catalog --> t_reward
catalog --> t_inv
elig    --> t_elig
saga    --> t_saga
audit   --> t_audit
relay   --> t_outbox

' Outbound async
ledger --> core   : POST /reservations, /commit, /release
relay  --> msk    : loyalty.redemption.*

@enduml
```

### 3.2 Sync Redemption Path

The standard happy-path: Member taps "Redeem" → Mobile BFF calls Redemption → Saga runs reserve / fulfil / commit in one thread. Applies to **Cashback**, **Bill-Payment Voucher**, and **Sweepstakes**. Most of the platform's redemption volume flows through here.

<p align="center">
  <img src="../images/level-3-loyalty-redemption-sync.svg" alt="">
</p>

```plantuml
@startuml level-3-loyalty-redemption-sync
title C4 Level 3 — loyalty-redemption — Sync redemption Saga (Cashback example)

skinparam sequence {
  ArrowColor       #073661
  LifeLineBorderColor #073661
  ParticipantBorderColor #073661
  ParticipantBackgroundColor #e8f0fe
  ParticipantFontColor #073661
}

participant "loyalty-mobile-bff"     as mbff
participant "REST API Layer"         as api
participant "Reward Catalogue"       as catalog
participant "Eligibility Engine"     as elig
participant "Redemption Orchestrator" as saga
database  "redemption_saga"          as saga_db
participant "Adapter Framework"      as spi
participant "CashbackAdapter"        as adp
participant "Ledger Client"          as ledger_cli
participant "loyalty-core" as core
participant "Payment Hub"            as payhub
participant "Outbox Relay"           as relay
queue     "loyalty.redemption.*"     as msk

mbff -> api : POST /redemptions\n{rewardId, idempKey}
api  -> catalog : load(rewardId)
catalog --> api : Reward {type=CASHBACK,\n cost, fulfillmentParams}

api  -> saga : submit(memberId, reward, idempKey)
activate saga

== Eligibility gate ==
saga -> elig : check(member, reward)
elig --> saga : OK | RejectedReason

== Phase 1 — reserve ==
saga -> ledger_cli : reserve(memberId, points)
ledger_cli -> core : POST /reservations
core --> ledger_cli : reservationId, status=HELD
saga -> saga_db : INSERT redemption_saga\n(status=RESERVED, reservationId)

== Phase 2 — fulfil (in-process) ==
saga -> saga_db : UPDATE status=FULFILLING
saga -> spi : dispatch(CASHBACK)
spi  -> adp : fulfil(saga, fulfillmentParams)
activate adp
adp  -> payhub : disburse(memberId, amount, currency)
payhub --> adp : success(externalRef)
adp  --> spi : FulfilmentResult.SUCCESS(externalRef)
deactivate adp
spi  --> saga : SUCCESS(externalRef)

== Phase 3 — commit ==
saga -> ledger_cli : commit(reservationId, externalRef)
ledger_cli -> core : POST /reservations/{id}/commit
core --> ledger_cli : ledgerEntryId
saga -> saga_db : UPDATE status=COMMITTED,\n external_ref=externalRef
saga -> relay : enqueueEvent(RedemptionCompleted)
relay --> msk : (async)

saga --> api : {status=COMMITTED, ledgerEntryId}
deactivate saga
api --> mbff : 200 OK

== Failure compensation (any phase) ==
note over saga, ledger_cli
  If fulfil() returns FAILURE,
  Saga calls release(reservationId)
  on loyalty-core → Reservation
  goes RELEASED, no Ledger entry,
  Saga row status=FAILED.
  Idempotency-key replay returns
  the same FAILED outcome.
end note

@enduml
```

**Why this design:**

- **Eligibility before reserve** — cheap rejects (wrong tier, segment, validity, per-Member cap) shouldn't tie up balance. Eligibility is read-only against `reward_eligibility` and the Member's projection in `loyalty-core`.
- **Saga state in `redemption_saga`** — explicit state machine, not implicit. Lets us audit "where did this redemption stop?" and lets the Resume Consumer (§3.3) pick up async outcomes by `externalRef`.
- **Adapter framework is an SPI** — `FulfillmentAdapter` is a Java interface with two methods: `FulfilmentResult fulfil(SagaContext)` and `Optional<FulfilmentResult> resume(String externalRef, PartnerOutcome)`. Adding a fifth adapter (e.g. Airtime) means implementing the interface + registering the bean — no Saga code changes.
- **Sweepstakes uses the same Saga** — the only difference is that the SweepstakesAdapter calls `loyalty-campaign`'s `Drawing.recordEntry` instead of Payment Hub. Same `reserve` → fulfil → `commit` shape. (T-13 internal touchpoint.)

### 3.3 3rd-Party Voucher Async Path

The one path that breaks the in-process Saga. Partner provisioning can take seconds to minutes. The Saga `submitVoucher()` returns immediately with `status=FULFILLING`; the Member's Reservation stays HELD; the partner pushes a webhook to `loyalty-integration-bridge`; Bridge republishes onto `loyalty.fulfillment.resume.v1`; the **Resume Consumer** picks it up and finishes the Saga.

<p align="center">
  <img src="../images/level-3-loyalty-redemption-async.svg" alt="">
</p>

```plantuml
@startuml level-3-loyalty-redemption-async
title C4 Level 3 — loyalty-redemption — 3rd-Party Voucher async resume

participant "loyalty-mobile-bff"  as mbff
participant "Redemption Orchestrator" as saga
participant "ThirdPartyVoucherAdapter" as adp
participant "3rd-Party Voucher\nProvider" as partner
participant "loyalty-integration-bridge" as bridge
queue     "loyalty.fulfillment.resume.v1" as msk_resume
participant "Resume Consumer" as resume_c
participant "Ledger Client" as ledger_cli
participant "loyalty-core" as core

== Submit (sync, returns FULFILLING) ==
mbff -> saga : submit(voucherRewardId)
saga -> adp  : fulfil(saga)
adp  -> partner : POST /provision\n{sku, customerRef=sagaId}
partner --> adp : 202 Accepted, externalRef
adp  --> saga : PENDING(externalRef)
saga -> saga : UPDATE status=FULFILLING,\n external_ref=externalRef
saga --> mbff : 202 Accepted\n{status=PENDING}
note right of mbff
  Mobile App shows
  "Voucher provisioning…"
  and polls.
end note

== Hours / minutes later — partner completes ==
partner -> bridge : POST /webhook/voucher\n{externalRef, status, voucherCode}
bridge -> bridge : Translate to canonical event;\n verify HMAC signature
bridge -> msk_resume : publish loyalty.fulfillment.resume.v1\n{externalRef, outcome, payload}

== Resume Consumer drains ==
msk_resume -> resume_c : resume event
resume_c -> saga : resumeSaga(externalRef, outcome)

alt outcome=SUCCESS
    saga -> ledger_cli : commit(reservationId, externalRef)
    ledger_cli -> core : POST /commit
    saga -> saga : UPDATE status=COMMITTED
else outcome=FAILURE
    saga -> ledger_cli : release(reservationId)
    ledger_cli -> core : POST /release
    saga -> saga : UPDATE status=FAILED
end

@enduml
```

**Notes on resume:**

- **Saga survives Pod restarts** — state is in `redemption_saga`, keyed by `externalRef` and `sagaId`. Resume Consumer is a stateless Kafka consumer; partition assignment is the only thing that ties a resume to a specific pod.
- **Webhook idempotency** — the Bridge dedupes by `externalRef` before publishing the resume event. The Resume Consumer additionally checks `redemption_saga.status` — a second-time resume on a `COMMITTED` saga is a silent no-op.
- **TTL on the partner** — if no webhook lands within the configured per-Reward timeout (default 24h), the Reservation TTL Sweeper inside `loyalty-core` ([level-3-loyalty-core.md §3.3](level-3-loyalty-core.md#33-scheduled--async-paths)) auto-releases the Reservation. The Saga is then marked FAILED on the next resume attempt; the partner's late success is treated as a billing dispute, not as a points event.

---

## 4. Component Inventory

| # | Component | Bounded context | Writes | Reads | Triggered by |
|---|---|---|---|---|---|
| 1 | **REST API Layer** | (Cross-cutting) | — | — | HTTPS / mTLS from `loyalty-mobile-bff`, `loyalty-admin-bff` |
| 2 | **Resume Consumer** | Fulfillment | — | — | MSK topic `loyalty.fulfillment.resume.v1` |
| 3 | **Reward Catalogue** | Reward | `reward`, `reward_inventory` | `reward`, `reward_inventory` | API: catalogue browse + admin CRUD |
| 4 | **Eligibility Engine** | Reward | — | `reward_eligibility`, Member projection (via Ledger Client) | Saga (before reserve) |
| 5 | **Redemption Orchestrator (Saga)** | Fulfillment | `redemption_saga` | `redemption_saga` | API: `submit()`; Resume Consumer: `resume()` |
| 6 | **Adapter Framework / SPI** | Fulfillment | — | — | Saga: `dispatch(rewardType)` |
| 7 | **CashbackAdapter** | Fulfillment | — | — | SPI |
| 8 | **BillPaymentVoucherAdapter** | Fulfillment | — | — | SPI |
| 9 | **ThirdPartyVoucherAdapter** | Fulfillment | — | — | SPI |
| 10 | **SweepstakesAdapter** | Fulfillment | — | — | SPI |
| 11 | **Ledger Client** | (Anti-corruption) | — | — | Saga (`reserve`/`commit`/`release`) |
| 12 | **Audit Log Writer** | (Cross-cutting) | `redemption_audit_log` | — | Every admin write via API (interceptor) |
| 13 | **Outbox Relay** | (Cross-cutting) | `outbox` | `outbox` | Internal scheduler (1s tick) |

**Notes on the Adapter framework:**

```text
interface FulfillmentAdapter {
  RewardType supportedType();
  FulfilmentResult fulfil(SagaContext ctx);
  Optional<FulfilmentResult> resume(String externalRef, PartnerOutcome o);
}
```

- Synchronous adapters (Cashback, Bill-Payment, Sweepstakes) return `SUCCESS` / `FAILURE` from `fulfil()` and never implement `resume()`.
- Asynchronous adapters (3rd-Party Voucher) return `PENDING(externalRef)` from `fulfil()` and implement `resume()`.
- The Saga is **the only consumer of the SPI** — adapters are not directly callable from the API layer.

---

## 5. Loyalty-Owned Tables in `loyalty-redemption RDS`

| Table | Purpose | Notes |
|---|---|---|
| **`reward`** | Per-Program Reward catalogue: type, cost, fulfillment params, validity window. | Authored in BEP; immutable once a Member has redeemed against it (versioned via `reward_revision`). |
| **`reward_inventory`** | Optional per-Reward inventory caps (e.g. limited-edition voucher run). | Atomic decrement on reserve; restored on release. |
| **`reward_eligibility`** | Per-Reward gates: Tier, segment, tenure, currency, per-Member cap. | Read-only on the hot path; authored in BEP. |
| **`redemption_saga`** | Saga state machine rows: `(sagaId, memberId, rewardId, reservationId, status, externalRef, idempotencyKey)`. | Single-writer = Orchestrator. Status transitions are explicit. |
| **`redemption_audit_log`** | Per-service audit trail for every BEP-originated Reward write. | ≥ 7-year retention. **Tamper-evident**: hash-chained + DB-immutable, nightly-sealed to S3 Object Lock WORM. |
| **`outbox`** | Transactional-outbox staging for `loyalty.redemption.*`. | Drained by Outbox Relay. |

S3 buckets used (not owned at the table level but owned by this service):

- **`loyalty-vouchers-*`** — Bill-Payment voucher PDFs, written by BillPaymentVoucherAdapter, returned to Mobile App as pre-signed URLs.

---

## 6. External Edges Re-exposed from L2

| Direction | Counterparty | Mechanism | Triggers which component |
|---|---|---|---|
| Sync inbound | `loyalty-mobile-bff` | REST/JSON via mTLS | REST API Layer → Reward Catalogue, Saga |
| Sync inbound | `loyalty-admin-bff` | REST/JSON via mTLS | REST API Layer → Reward Catalogue (admin) |
| Sync outbound | `loyalty-core` | REST/JSON via mTLS | Ledger Client (`reserve`/`commit`/`release`) |
| Sync outbound | `loyalty-campaign` | REST/JSON via mTLS | SweepstakesAdapter (`Drawing.recordEntry`) |
| Sync outbound | Payment Hub | REST/gRPC | CashbackAdapter (disbursement) |
| Sync outbound | 3rd-Party Voucher Provider | REST/JSON | ThirdPartyVoucherAdapter (provision) |
| Storage | S3 (`loyalty-vouchers-*`) | AWS SDK | BillPaymentVoucherAdapter (PDF put + pre-signed URL) |
| Async inbound | Shared MSK Kafka — `loyalty.fulfillment.resume.v1` (from Bridge) | Kafka consumer | Resume Consumer → Saga |
| Async outbound | Shared MSK Kafka — `loyalty.redemption.*` | Kafka producer (Outbox Relay) | Outbox Relay |
| JDBC | `loyalty-redemption RDS` | JDBC (HikariCP) | All components owning a table |

---

## 7. Invariants & Cross-References

- **Two-phase redemption with a separate Reservation table.** The Saga state in `redemption_saga` mirrors but does not replace the Reservation row in `loyalty-core`.
- **In-process Saga for sub-second paths, async only for the partner-dependent path.** This is why Reward + Fulfillment are co-deployed.
- **Adapters are an SPI, not a kitchen-sink interface** — adding an adapter is bounded: implement one Java interface, register one bean, add one `RewardType` enum value, add one row of test coverage. Saga code does not change.
- **The Saga is the only writer to `redemption_saga`** — Adapters return outcomes; the Saga records them. This keeps the state machine auditable.
- **Compensation by `release()`, not by deleting** — failed fulfilments release the Reservation; they never UPDATE or DELETE Ledger rows (P5).
- **Voucher PDF storage is in S3, not in Postgres** — keeps the RDS small and offloads CDN-able artefacts to the right tier.

Next L3 view: [`level-3-loyalty-campaign.md`](level-3-loyalty-campaign.md) — Campaign Aggregate, Drawing Scheduler, Winner Selection.

---

*End of document.*
