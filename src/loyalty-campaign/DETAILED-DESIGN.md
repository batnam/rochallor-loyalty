# loyalty-campaign — Detailed Design & User Guide

A self-contained companion to [C4 L3 `loyalty-campaign`](../../docs/c4/level-3-loyalty-campaign.md),
the internal API ([`loyalty-campaign.yaml`](../../docs/openapi/internal/loyalty-campaign.yaml)) and
the domain events ([`asyncapi/loyalty-campaign.yaml`](../../docs/asyncapi/loyalty-campaign.yaml)).

---

## 0. What this service is

Two co-deployed bounded contexts:

- **Campaign** — marketing-driven, time-bounded earning multipliers. `loyalty-campaign` *owns and exposes*
  the multiplier rule; it never evaluates it (that is the DSL Interpreter in `loyalty-earning`).
- **Drawing** — sweepstakes. Members enter, a winner is drawn fairly and auditably, and the prize is
  fulfilled by reusing the existing Reward pipeline in `loyalty-redemption` (the T-13 touchpoint).

---

## 1. Bounded context & neighbours

- **Inbound:** `loyalty-mobile-bff` (list active), `loyalty-admin-bff` (Campaign + Drawing CRUD),
  `loyalty-redemption` (the T-13 entry surface).
- **Outbound:** none synchronous — campaign only *emits* `loyalty.campaign.*` through the transactional
  outbox. Prize fulfilment is delegated to `loyalty-redemption` (the only service that fulfils a Reward).
- **Owns:** `loyalty-campaign RDS` (campaign / drawing / entry / winner) and the `loyalty.campaign.*` topics.

---

## 2. The two state machines

```
Campaign:  DRAFT ──▶ SCHEDULED ──▶ LIVE ──▶ ENDED ──▶ ARCHIVED
              └──────────┴───────────────────────────▶ ARCHIVED   (archive before going live)
              └──▶ LIVE                                            (DRAFT may go live directly)

Drawing:   OPEN ──▶ CLOSED   (K winners selected)
              └───▶ VOID     (zero entries at draw time)
```

`ARCHIVED` (Campaign) and `CLOSED`/`VOID` (Drawing) are terminal — every further transition is rejected.
The LIVE transition is **approval-gated** when the Campaign is economic; the close/void transition is the
only writer of a Drawing's terminal state and is guarded by a pessimistic row lock.

---

## 3. Winner Selection (the auditable heart) — L3 §3.3

Pure function `WinnerSelection.select(drawingId, drawAt, hmacSecret, entries, strategy, winnersCount)`:

1. `K = min(winnersCount, N)`; `N=0` → no winners (Drawing closes VOID).
2. **Seed** (`SEEDED_RNG`/`WEIGHTED`): `seedHex = HMAC-SHA256(secret, "drawingId|drawAt")`. The secret is
   the only non-public input — with it plus the immutable `entry_id` order and frozen per-entry weights, an
   auditor reproduces the exact winners. A `java.util.Random` is seeded from the HMAC's leading 8 bytes;
   the PRNG is deterministic (replay needs that) — unpredictability comes from the secret, not the PRNG.
3. **Draw without replacement:** uniform (partial Fisher–Yates) for `SEEDED_RNG`, cumulative-weight for
   `WEIGHTED`. `FIRST_N` takes the first K by arrival with **no seed** (`seedHex = null`).
4. Each winner carries `winnerIndex` = its position in the `entry_id`-ordered set, so K winners hold K
   distinct indices (`UNIQUE(drawing_id, winner_index)` holds by construction).

`winner_record` rows are insert-only (DB trigger + `@Immutable`); `GET /drawings/{id}/winners` is the audit
view.

---

## 4. The entry path (T-13) — L3 §3.2

<p align="center">
  <img src="images/campaign-dd-seq-entry.svg" alt="T-13 entry path — redemption Saga to window-gated idempotent INSERT sequence">
</p>

```plantuml
@startuml campaign-dd-seq-entry
title loyalty-campaign — T-13 entry path (wired: redemption Saga → window-gated idempotent INSERT)

participant orch   as "RedemptionOrchestrator"
participant adp    as "SweepstakesAdapter"
participant client as "CampaignClient"
participant api    as "EntryController"
participant svc    as "EntryService"
database    db     as "campaign RDS (1 txn)"

orch -> adp : fulfil(SagaContext)
activate adp
adp -> adp : drawingId = ctx.param("drawingId")\nidempotencyKey = "saga-{sagaId}-drawing-{drawingId}"
adp -> client : recordEntry(drawingId, memberId, sagaId, key, weight?)
activate client
client -> api : POST /drawings/{id}/entries\n{memberId, sagaId, idempotencyKey, weight?}

== campaign side (1 txn) ==
api -> api : memberId & idempotencyKey present?\n(else 400 BAD_ENTRY)
api -> svc : recordEntry(drawingId, memberId, sagaId, key, weight)
activate svc
svc -> db : drawing exists?  (else 404 DRAWING_NOT_FOUND)
svc -> db : INSERT … SELECT … \n  WHERE drawing OPEN AND now() BETWEEN window\n  ON CONFLICT (idempotency_key) DO NOTHING
alt 1 row inserted (new entry)
  svc -> db : load entry by idempotencyKey
  svc --> api : RecordResult(entry, created=true)
  api --> client : 201 Created {entryId, …}
else 0 rows — disambiguate by key
  svc -> db : entry by idempotencyKey?
  alt key already present (idempotent replay)
    svc --> api : RecordResult(existing, created=false)
    api --> client : 200 OK {entryId, …}
  else none (drawing closed / window passed)
    svc --> api : 409 DRAWING_CLOSED
    api --> client : 409 DRAWING_CLOSED
  end
end
deactivate svc

== redemption maps the response onto the Saga ==
alt 2xx (201 new OR 200 replay — both carry entryId)
  client --> adp : entryId  (String ref; CampaignClient ignores 201 vs 200)
  adp --> orch : FulfilmentResult.SUCCESS(entryRef)
else 4xx/5xx (e.g. 409 DRAWING_CLOSED, or entryId null)
  client --> adp : RuntimeException
  adp --> orch : FulfilmentResult.FAILURE("campaign error: …")\n→ Saga compensates (release reservation, restore inventory)
end
deactivate client
deactivate adp
@enduml
```

`POST /drawings/{id}/entries {memberId, sagaId, idempotencyKey, weight?}`, called by
`loyalty-redemption`'s SweepstakesAdapter inside a Member's Saga:

```
recordEntry → INSERT … SELECT … WHERE drawing OPEN AND now() BETWEEN window
                       ON CONFLICT (idempotency_key) DO NOTHING
  ├─ 1 row  → 201 (new entry)
  └─ 0 rows → key exists? → 200 (idempotent replay)        : 409 DRAWING_CLOSED (closed / window passed)
```

A single conditional INSERT — no SELECT-then-INSERT race — makes the window check and the idempotency
guarantee atomic. A duplicate is a successful no-op; we never fail the calling Saga on a replay.

---

## 5. Winner Selection trigger + prize hand-off

<p align="center">
  <img src="images/campaign-dd-seq-winner.svg" alt="Winner selection trigger, outbox emit & prize hand-off sequence">
</p>

```plantuml
@startuml campaign-dd-seq-winner
title loyalty-campaign — Winner selection trigger, outbox emit & prize hand-off

participant sch        as "DrawingScheduler\n(@Scheduled, ShedLock)"
participant sel        as "WinnerSelectionService"
participant algo       as "WinnerSelection (pure fn)"
participant relay      as "OutboxRelay"
queue       msk        as "loyalty.campaign.* (MSK)"
participant redemption as "loyalty-redemption"
database    db         as "campaign RDS"

sch -> db : find OPEN drawings, draw_at <= now()
loop each due drawing  (own txn — one failure can't block the rest)
  sch -> sel : selectWinner(drawingId)
  activate sel
  sel -> db : SELECT … FOR UPDATE  (pessimistic lock)
  alt status terminal (duplicated fire)
    sel --> sch : no-op
  else OPEN
    sel -> db : entries ORDER BY entry_id  (immutable order)
    alt N = 0
      sel -> db : drawing.markVoid()  (status=VOID)
      sel -> relay : enqueue DrawingVoid  (key=drawingId)
    else N > 0
      sel -> algo : select(drawingId, drawAt, hmacSecret, entries, strategy, K=min(winnersCount,N))
      algo --> sel : K winners {memberId, winnerIndex, seedHex}
      sel -> db : INSERT K winner_record rows  (insert-only)
      sel -> db : drawing.close()  (status=CLOSED)
      sel -> relay : enqueue 1× DrawingCompleted  (key=drawingId)
      sel -> relay : enqueue K× WinnerSelected  (key=memberId)
    end
  end
  deactivate sel
end

== Outbox relay drains async (PT1S tick, ShedLock) ==
relay -> msk : publish PENDING rows
msk -> redemption : WinnerSelected → system-initiated redemption per prize (T-13)
@enduml
```

```
Drawing Scheduler (@Scheduled, ShedLock)  → finds OPEN drawings with draw_at <= now
  → WinnerSelectionService.selectWinner(id)   (one txn per drawing)
     ├─ SELECT … FOR UPDATE; status != OPEN → no-op (duplicated fire)
     ├─ N entries (entry_id order)
     ├─ N=0 → drawing.markVoid(); outbox DrawingVoid
     └─ N>0 → K winner_record rows; drawing.close();
              outbox 1×DrawingCompleted (key=drawingId) + K×WinnerSelected (key=memberId)

(downstream) loyalty-redemption issues a system-initiated redemption for each prize — T-13.
```

---

## 6. Authoring & the approval gate — L3 §3.2

<p align="center">
  <img src="images/campaign-dd-seq-authoring.svg" alt="Campaign authoring, approval gate, drawing creation & audit chain sequence">
</p>

```plantuml
@startuml campaign-dd-seq-authoring
title loyalty-campaign — Authoring, approval gate, drawing creation & audit chain

participant abff  as "loyalty-admin-bff"
participant capi  as "CampaignController"
participant csvc  as "CampaignService"
participant dapi  as "DrawingController"
participant dsvc  as "DrawingService"
participant audit as "AuditLogWriter"
database    db    as "campaign RDS (1 txn)"

== Create campaign (always DRAFT) ==
abff -> capi : POST /programs/{id}/campaigns\n{name, startsAt, endsAt, multiplierRule?, targetSegment?}  (X-Actor)
capi -> csvc : create(actor, programId, …)
activate csvc
csvc -> db : INSERT campaign (status=DRAFT;\n multiplierRule/targetSegment stored as JSON)
csvc -> audit : record(actor, CREATE, Campaign, id, before=null, after)
audit -> db : prevHash = last row hash;\n rowHash = SHA-256(prev ‖ actor ‖ action ‖ … ‖ after);\n INSERT campaign_audit_log
deactivate csvc
capi --> abff : 201 Created {status=DRAFT}

== Transition campaign (approval-gated when economic) ==
abff -> capi : PATCH /campaigns/{id} {status, bepApprovalRef?}
capi -> csvc : transition(actor, id, target, bepApprovalRef)
activate csvc
csvc -> db : load campaign
note right of csvc
  !canTransitionTo(target)         -> 409 ILLEGAL_TRANSITION
  target=LIVE && isEconomic()
    && blank bepApprovalRef        -> 409 MISSING_APPROVAL
end note
csvc -> db : UPDATE campaign status
csvc -> audit : record(actor, TRANSITION, Campaign, id, before, after)
audit -> db : append hash-chained row (insert-only trigger)
deactivate csvc
capi --> abff : 200 OK {new status}

== Add a Drawing (OPEN, inherits programId) ==
abff -> dapi : POST /campaigns/{id}/drawings\n{prize, entryWindowStart, entryWindowEnd, drawAt, selectionStrategy, winnersCount}
dapi -> dsvc : create(actor, campaignId, …)
activate dsvc
dsvc -> db : load campaign  (404 if absent)
dsvc -> db : INSERT drawing (status=OPEN, inherits programId)
dsvc -> audit : record(actor, CREATE, Drawing, id, before=null, after)
deactivate dsvc
dapi --> abff : 201 Created {status=OPEN}
@enduml
```

- `POST /programs/{id}/campaigns` creates a Campaign as **DRAFT**.
- `PATCH /campaigns/{id}` transitions it. An illegal move is `409 ILLEGAL_TRANSITION`; a **LIVE** transition
  on an **economic** Campaign (one with a `multiplierRule`) without a `bepApprovalRef` is
  `409 MISSING_APPROVAL`, mirroring core/earning/redemption's confirm seam.
- `POST /campaigns/{id}/drawings` adds an OPEN Drawing (inheriting the Campaign's `programId`).
- Every admin write is recorded in `campaign_audit_log` — SHA-256 hash-chained + DB-immutable.

---

## 7. Data & config reference

**Tables** (`loyalty-campaign RDS`): `campaign`, `drawing`, `drawing_entry`, `winner_record` (insert-only),
`campaign_audit_log` (insert-only), `outbox`, `shedlock`. Flyway `V1__baseline.sql` + `V2__seed_sample.sql`.

**Config** (`campaign.*`): `topics.{drawing-completed,winner-selected,drawing-void}`, `default-program-id`,
`default-program-code`, `selection.hmac-secret`, `scheduler.poll-cron`, `outbox.relay-batch-size`.

---

## 8. Implementation notes & divergences

- **Campaign status enum** — follows the OpenAPI contract (`DRAFT/SCHEDULED/LIVE/ENDED/ARCHIVED`), which is
  the authoritative wire interface. The C4 prose still names the older `DRAFT/ACTIVE/ARCHIVED`; the OpenAPI
  supersedes it.
- **`DrawingVoid` event** — the C4 §3.3 designs a distinct event for zero-entry drawings. We emit
  `DrawingVoid` on a distinct configurable topic (`loyalty.campaign.drawing_void.v1`) rather than overload
  `DrawingCompleted` with an empty winner set. Now catalogued in the `asyncapi` (and `event-catalogue.md`),
  partitioned by `drawingId`.
- **T-13 seam — now wired.** campaign's entry contract is `{memberId, sagaId, idempotencyKey, weight?}` →
  `DrawingEntry`. `loyalty-redemption`'s `CampaignClient`/`SweepstakesAdapter` now send the full contract —
  the `idempotencyKey` is derived from the Saga key (`saga-{sagaId}-drawing-{drawingId}`) so a Saga retry
  replays the same entry rather than duplicating — and read back `entryId` as the adapter's external ref.
  Pinned on the redemption side by `CampaignClientTest`.
- **`prizeRewardId` on `WinnerSelected`** — null in v1; the prize lives in the Drawing's open `prize` JSON
  and prize fulfilment is a direct call to `loyalty-redemption` (per the asyncapi note), not driven by the
  event.
- **Jackson 2/3 split** — Spring Boot 4's web layer is Jackson 3; the platform pins Jackson 2. Open-JSON DTO
  fields (`multiplierRule`, `targetSegment`, `prize`) are typed `Object` (Map/List trees), not tree nodes —
  the same approach redemption uses.
- **No outbound HTTP** — campaign serves REST and emits events only; the IT needs just Postgres + Kafka.

---

## 9. Run & operate

```bash
./gradlew test          # 26 unit + 7 Testcontainers IT (Postgres + Kafka)
./gradlew bootRun       # needs Postgres + Kafka
```

Requires a **JDK 25** toolchain. Flyway owns the schema (`ddl-auto: validate`). The Outbox Relay drains on a
1s ShedLock-guarded tick; the Drawing Scheduler polls per `scheduler.poll-cron` (default every minute),
ShedLock-guarded. The HMAC selection secret is rotated annually by platform SRE; `winner_record` retains the
seed hex so any past draw stays re-verifiable.
