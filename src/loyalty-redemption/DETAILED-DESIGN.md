# loyalty-redemption — Detailed Design & User Guide

A self-contained companion to [C4 L3 `loyalty-redemption`](../../docs/c4/level-3-loyalty-redemption.md),
the internal API ([`loyalty-redemption.yaml`](../../docs/openapi/internal/loyalty-redemption.yaml)) and
the domain events ([`asyncapi/loyalty-redemption.yaml`](../../docs/asyncapi/loyalty-redemption.yaml)).

---

## 0. What this service is

The two-phase **redemption Saga**. A Member taps "Redeem" in the app → Mobile BFF calls
`POST /redemptions` → the Saga:

1. **Eligibility** — gate cheaply (tier / segment / currency / tenure / per-Member cap / balance).
2. **Reserve** (Phase 1) — hold points in `loyalty-core` (`POST /reservations`, status `HELD`).
3. **Fulfil** — dispatch the `FulfillmentAdapter` for the Reward Type.
4. **Commit** (Phase 2) on adapter SUCCESS (`POST /reservations/{id}/commit` → immutable `Redeemed`
   entry), or **Release** on FAILURE (balance restored, no Ledger entry).

Three adapters are synchronous (sub-second, in one request thread); the 3rd-Party Voucher adapter is
**asynchronous** — it returns `PENDING`, the Saga parks at `FULFILLING`, and the partner webhook resumes
it later. Outcomes are published via the transactional outbox.

---

## 1. Bounded context & neighbours

- **Inbound:** `loyalty-mobile-bff` (submit / poll), `loyalty-admin-bff` (Reward CRUD), and
  `loyalty.fulfillment.resume.v1` (partner webhook, via the bridge).
- **Outbound:** `loyalty-core` (Reservation API + Member projection), **Payment Hub** (Cashback),
  **3rd-Party Voucher provider** (voucher provisioning), **`loyalty-campaign`** (Sweepstakes entry —
  the T-13 surface, now wired to campaign's shipped `POST /drawings/{id}/entries` contract),
  **S3** (voucher PDFs — deferred).
- **Owns:** `loyalty-redemption RDS` (Reward catalogue + Saga state) and the `loyalty.redemption.*`
  topics.

It is a **client** of core's Ledger — it never touches `point_ledger`. Two bounded contexts (Reward,
Fulfillment) are co-deployed so adapter dispatch is an in-process call.

---

## 2. The Saga state machine (the heart of the service)

```
RESERVED ──▶ FULFILLING ──▶ COMMITTED   (reserve → fulfil → commit)
   │             │      └──▶ RELEASED    (TTL expiry during async wait)
   │             └─────────▶ FAILED      (adapter / partner failure → release)
   ├──▶ RELEASED                         (released before fulfilment)
   └──▶ FAILED
```

`COMMITTED`, `RELEASED`, `FAILED` are **terminal** — they reject every further transition. The
Orchestrator is the only writer; `SagaStatus.canTransitionTo` enforces legality, so an out-of-order
move (e.g. committing a released saga) throws rather than corrupting the machine. This is also what
makes a **duplicate resume** on an already-finished saga a safe no-op (L3 §3.3 webhook idempotency).

---

## 3. The Fulfillment SPI

```java
interface FulfillmentAdapter {
  RewardType supportedType();
  FulfilmentResult fulfil(SagaContext ctx);                              // SUCCESS | FAILURE | PENDING
  Optional<FulfilmentResult> resume(String externalRef, PartnerOutcome); // async only; default empty
}
```

- `AdapterRegistry` resolves exactly one adapter per `RewardType` at wiring time; a duplicate
  registration fails fast, an unknown type is a hard error (never a silent skip).
- **Sync** adapters (Cashback → Payment Hub, Bill-Payment → synthetic voucher, Sweepstakes → campaign)
  return `SUCCESS`/`FAILURE` and never implement `resume`.
- **Async** adapter (3rd-Party Voucher → partner) returns `PENDING(externalRef)` and implements
  `resume` — turning the partner's verdict into SUCCESS (commit, keying off the issued voucher code) or
  FAILURE (release).

Adding a fifth Reward Type: implement the interface, register the bean, add the enum value. No Saga code
changes.

---

## 4. The sync path — `submit()` → Cashback example

<p align="center">
  <img src="images/redemption-dd-seq-sync.svg" alt="Sync redemption Saga sequence (Cashback)">
</p>

```plantuml
@startuml redemption-dd-seq-sync
title loyalty-redemption — Sync redemption Saga (Cashback example)

participant mbff   as "loyalty-mobile-bff"
participant api    as "RedemptionController"
participant orch   as "RedemptionOrchestrator"
participant cat    as "RewardCatalogue"
participant elig   as "EligibilityEngine"
participant ledger as "LedgerClient"
participant core   as "loyalty-core"
participant adp    as "CashbackAdapter"
participant payhub as "Payment Hub"
participant relay  as "OutboxRelay"
database    db     as "redemption RDS (1 txn)"

mbff -> api : POST /redemptions {memberId, programId, rewardId}\n(Idempotency-Key)
api -> orch : submit(...)
activate orch

== Idempotency replay ==
orch -> db : exists redemption_idempotency(key)?
alt key already processed
  orch -> db : load original saga
  orch --> api : RedemptionResult(original, replayed=true)
  api --> mbff : 200 OK {status, ledgerEntryId}
end

== Eligibility gate (cheap rejects, no row yet) ==
orch -> cat : get(rewardId)
cat --> orch : Reward {type=CASHBACK, pointCost, fulfillmentParams}
orch -> ledger : projection(memberId, programId)
ledger -> core : GET member projection
core --> ledger : {redeemableBalance}
orch -> elig : check(active, rules, member, cost, priorRedemptions)
elig --> orch : eligible | RejectedReason
note right of orch : not eligible -> 409 <reason>

== Inventory hold (atomic) ==
orch -> cat : tryReserveInventory(rewardId)
cat -> db : conditional UPDATE reward_inventory
cat --> orch : true | false
note right of orch : false -> 409 INVENTORY_EXHAUSTED

== Phase 1 — reserve points ==
orch -> ledger : reserve(memberId, programId, cost, rewardId, key)
ledger -> core : POST /reservations
core --> ledger : reservationId, status=HELD
note right of orch : core 409 -> restoreInventory + 409 INSUFFICIENT_BALANCE
orch -> db : INSERT redemption_saga (RESERVED) -> FULFILLING

== Phase 2 — fulfil (in-process) ==
orch -> adp : fulfil(SagaContext)
activate adp
adp -> payhub : disburse(memberId, amount, currency)
payhub --> adp : success(externalRef)
adp --> orch : FulfilmentResult.SUCCESS(externalRef)
deactivate adp

== Phase 3 — commit ==
orch -> ledger : commit(reservationId, externalRef)
ledger -> core : POST /reservations/{id}/commit
core --> ledger : ledgerEntryId
orch -> db : UPDATE saga = COMMITTED, external_ref
orch -> relay : enqueue RedemptionCompleted (in txn)
orch -> db : INSERT redemption_idempotency(key -> sagaId)
orch --> api : RedemptionResult(COMMITTED, ledgerEntryId)
deactivate orch
api --> mbff : 200 OK {status=COMMITTED, ledgerEntryId}

== Failure compensation (adapter FAILURE) ==
note over orch, core
  fulfil() returns FAILURE:
    ledger.release(reservationId)   -> core POST /reservations/{id}/release
    cat.restoreInventory(rewardId)
    saga.fail("PARTNER_FAILURE")    -> FAILED
    enqueue RedemptionFailed
  All inside the one Saga transaction.
  An Idempotency-Key replay returns the same FAILED outcome.
end note
@enduml
```

```
POST /redemptions {memberId, programId, rewardId}  (Idempotency-Key header)
  ├─ idempotency replay?  → return original saga
  ├─ Eligibility.check(reward, member projection, priorRedemptions)  → 409 on reject
  ├─ reward_inventory: atomic conditional decrement                  → 409 INVENTORY_EXHAUSTED
  ├─ core POST /reservations                                          → 409 INSUFFICIENT_BALANCE (restore inv)
  ├─ saga = RESERVED → FULFILLING
  ├─ CashbackAdapter.fulfil → Payment Hub disburse → SUCCESS(ref)
  ├─ core POST /reservations/{id}/commit → ledgerEntryId
  ├─ saga.commit(entryId, ref) → COMMITTED ; outbox RedemptionCompleted
  └─ 200 {status: COMMITTED, ledgerEntryId}
```

On adapter FAILURE: `release` the reservation, restore inventory, `saga.fail` → `FAILED`, outbox
`RedemptionFailed`. The in-process commit/release HTTP calls run inside the Saga transaction — the
design choice that keeps the sub-second paths consistent.

---

## 5. The async path — 3rd-Party Voucher

<p align="center">
  <img src="images/redemption-dd-seq-async.svg" alt="3rd-Party Voucher async resume sequence">
</p>

```plantuml
@startuml redemption-dd-seq-async
title loyalty-redemption — 3rd-Party Voucher async resume

participant mbff    as "loyalty-mobile-bff"
participant orch    as "RedemptionOrchestrator"
participant adp     as "ThirdPartyVoucherAdapter"
participant partner as "3rd-Party Voucher Provider"
participant bridge  as "loyalty-integration-bridge"
queue       resume  as "loyalty.fulfillment.resume.v1"
participant rc      as "ResumeConsumer"
participant cat     as "RewardCatalogue"
participant ledger  as "LedgerClient"
participant core    as "loyalty-core"
participant relay   as "OutboxRelay"
database    db      as "redemption RDS"

== Submit (sync; eligibility + reserve as in sync path, then parks) ==
mbff -> orch : submit(voucherRewardId)
orch -> adp : fulfil(SagaContext)
adp -> partner : POST /provision {sku, customerRef=sagaId}
partner --> adp : 202 Accepted, externalRef
adp --> orch : FulfilmentResult.PENDING(externalRef)
orch -> db : UPDATE saga = FULFILLING, external_ref = externalRef
orch --> mbff : 202 Accepted {status=FULFILLING}
note right of mbff
  App shows "voucher provisioning…"
  and polls GET /redemptions/{id}.
  Reservation stays HELD in core.
end note

== Minutes / hours later — partner completes ==
partner -> bridge : POST /webhook/voucher {externalRef, status, voucherCode}
bridge -> bridge : verify HMAC; dedupe by externalRef;\n map to canonical event
bridge -> resume : publish {externalRef, outcome, payload}

== Resume Consumer drains ==
resume -> rc : resume event
rc -> orch : resume(externalRef, PartnerOutcome)
activate orch
orch -> db : find saga by external_ref
alt unknown ref OR terminal saga
  orch --> rc : no-op (duplicate webhook is safe)
end
orch -> adp : resume(externalRef, outcome)
adp --> orch : SUCCESS(voucherCode) | FAILURE

alt outcome = SUCCESS
  orch -> ledger : commit(reservationId, voucherCode)
  ledger -> core : POST /reservations/{id}/commit
  core --> ledger : ledgerEntryId
  orch -> db : UPDATE saga = COMMITTED, external_ref = voucherCode
  orch -> relay : enqueue RedemptionCompleted
else outcome = FAILURE
  orch -> ledger : release(reservationId)
  ledger -> core : POST /reservations/{id}/release
  orch -> cat : restoreInventory(rewardId)
  orch -> db : UPDATE saga = FAILED
  orch -> relay : enqueue RedemptionFailed
end
deactivate orch

note over partner, core
  If no webhook lands within the reservation TTL,
  core's Reservation TTL Sweeper auto-releases the hold;
  the saga is then marked FAILED on the next resume attempt.
end note
@enduml
```

```
submit() → ThirdPartyVoucherAdapter.fulfil → partner /provision (202) → PENDING(externalRef)
         → saga FULFILLING, external_ref = correlation ref → 202 {status: FULFILLING}    (client polls)

(minutes later) partner webhook → bridge → loyalty.fulfillment.resume.v1 {externalRef, outcome, payload}
         → ResumeConsumer → Orchestrator.resume(externalRef, outcome)
            ├─ find saga by external_ref; terminal → no-op
            ├─ adapter.resume → SUCCESS(voucherCode) | FAILURE
            ├─ SUCCESS: core commit → COMMITTED (external_ref ← voucherCode) ; outbox RedemptionCompleted
            └─ FAILURE: core release + restore inventory → FAILED ; outbox RedemptionFailed
```

If no webhook lands within the reservation TTL, core's Reservation TTL Sweeper auto-releases; the
saga is then `RELEASED`/`FAILED` on the next attempt.

---

## 6. Authoring & the approval gate

<p align="center">
  <img src="images/redemption-dd-seq-authoring.svg" alt="Reward authoring, approval gate & audit chain sequence">
</p>

```plantuml
@startuml redemption-dd-seq-authoring
title loyalty-redemption — Reward authoring, approval gate & audit chain

participant abff  as "loyalty-admin-bff"
participant api   as "RewardController"
participant cat   as "RewardCatalogue"
participant elig  as "EligibilityEngine"
participant audit as "AuditLogWriter"
database    db    as "redemption RDS (1 txn)"

== Create (always DRAFT) ==
abff -> api : POST /programs/{id}/rewards\n{rewardTypeCode, name, pointCost, eligibility, inventoryTotal}  (X-Actor)
api -> cat : createReward(actor, ...)
activate cat
cat -> db : reward_type exists?  (else 400 UNKNOWN_REWARD_TYPE)
cat -> db : INSERT reward (status=DRAFT)
cat -> db : INSERT reward_eligibility / reward_inventory (if supplied)
cat -> audit : record(actor, CREATE, Reward, id, before=null, after)
audit -> db : prevHash = last row hash;\n rowHash = SHA-256(prev ‖ actor ‖ action ‖ ... ‖ after);\n INSERT redemption_audit_log
deactivate cat
api --> abff : 201 Created {status=DRAFT}

== Update (approval-gated) ==
abff -> api : PATCH /rewards/{id}\n{status?, pointCost?, inventoryTotal?, bepApprovalRef?}
api -> cat : updateReward(actor, ...)
activate cat
note right of cat
  needsApproval = (status == ACTIVE) OR (pointCost != null)
  needsApproval && blank bepApprovalRef -> 409 MISSING_APPROVAL
  ARCHIVED + inventory bumps apply directly (no gate)
  a pointCost change bumps reward_revision
end note
cat -> db : changePointCost / activate / archive / UPDATE inventory
cat -> audit : record(actor, UPDATE, Reward, id, before, after)
audit -> db : append hash-chained row (insert-only trigger)
deactivate cat
api --> abff : 200 OK {updated reward}

== Eligible read (read-only projection) ==
abff -> api : GET /programs/{id}/rewards/eligible?memberId=
api -> cat : listActive(programId)
loop each active reward
  api -> elig : isEligible(rules, member projection, priorRedemptions)
end
api --> abff : 200 OK [rewards the member can redeem]
@enduml
```

- `POST /programs/{id}/rewards` creates a Reward as **DRAFT**.
- `PATCH /rewards/{id}` — `status=ACTIVE` or any `pointCost` change is **approval-gated**: it requires a
  `bepApprovalRef` (else `409 MISSING_APPROVAL`), mirroring core/earning's confirm seam. `ARCHIVED` and
  inventory bumps apply directly. A `pointCost` change bumps `reward_revision`.
- `GET /programs/{id}/rewards/eligible?memberId=` runs the active catalogue through the Eligibility
  Engine for one member (read-only).
- Every admin write is recorded in `redemption_audit_log` — SHA-256 hash-chained + DB-immutable
  (insert-only trigger).

---

## 7. Data & config reference

**Tables** (`loyalty-redemption RDS`): `reward_type` (seeded), `reward`, `reward_inventory`,
`reward_eligibility`, `redemption_saga`, `redemption_idempotency`, `redemption_audit_log`, `outbox`,
`shedlock`. Flyway `V1__baseline.sql` + `V2__seed_reward_types.sql`.

**Config** (`redemption.*`): `topics.{fulfillment-resume,redemption-completed,redemption-failed}`,
`core.base-url`, `payment-hub.base-url`, `voucher-partner.base-url`, `campaign.base-url`,
`reservation-ttl-seconds`, `outbox.relay-batch-size`, `default-program-id`.

---

## 8. Implementation notes

- **Jackson 2/3 split:** Spring Boot 4's web layer is Jackson 3; the platform pins Jackson 2. DTO fields
  carrying open JSON (`fulfillmentParams`, `eligibility`, `parameterSchema`) are typed `Object`
  (Map/List trees), not Jackson tree nodes — the same approach earning uses for its DSL.
- **RestClients pinned to HTTP/1.1** — the JDK client otherwise negotiates HTTP/2, flaky against some
  servers and the WireMock stubs.
- **Eligibility is pure** — it takes a `MemberSnapshot` + `EligibilityRules` value object, so it is
  fully unit-testable independent of what core's projection exposes.

---

## 9. Run & operate

```bash
./gradlew test          # 41 unit + 7 Testcontainers IT (Postgres + Kafka + WireMock-stubbed externals)
./gradlew bootRun       # needs Postgres, Kafka, and loyalty-core
```

Requires a **JDK 25** toolchain. Flyway owns the schema (`ddl-auto: validate`). The Outbox Relay drains
on a 1s ShedLock-guarded tick. The IT stubs core + Payment Hub + the voucher partner with one WireMock
server, so the suite runs with no sibling service.
