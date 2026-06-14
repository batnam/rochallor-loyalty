# loyalty-campaign

Campaigns + Drawings (sweepstakes) service — C4 L3 [`loyalty-campaign`](../../docs/c4/level-3-loyalty-campaign.md).
Internal API: [`loyalty-campaign.yaml`](../../docs/openapi/internal/loyalty-campaign.yaml) ·
events: [`asyncapi/loyalty-campaign.yaml`](../../docs/asyncapi/loyalty-campaign.yaml).

Co-locates two bounded contexts:

- **Campaign** — per-Program, time-bounded earning multipliers. Authored in BEP (`DRAFT → SCHEDULED →
  LIVE → ENDED → ARCHIVED`); the LIVE transition is **approval-gated** when the Campaign is economic
  (carries a multiplier). The multiplier rule is *exposed* to `loyalty-earning`, never evaluated here.
- **Drawing** — sweepstakes. Members enter via `loyalty-redemption`'s SweepstakesAdapter (the **T-13**
  surface); when a Drawing reaches its `draw_at`, the in-pod **Drawing Scheduler** fires **Winner
  Selection**, which picks K winners without replacement, records immutable winner rows, and emits
  `loyalty.campaign.*`. Prize fulfilment is delegated back to `loyalty-redemption`.

## Invariants

- **Auditable winner selection** — `SEEDED_RNG`/`WEIGHTED` derive the seed from `HMAC-SHA256(secret,
  drawingId|drawAt)`; the seed hex + the immutable `entry_id` order + frozen weights reproduce any draw.
  `FIRST_N` is arrival-deterministic (no seed). `winner_record` is insert-only; `UNIQUE(drawing_id,
  winner_index)` blocks duplicate slots.
- **Window-gated, idempotent entries** — one conditional INSERT (`WHERE status=OPEN AND now() BETWEEN
  window`) with `ON CONFLICT (idempotency_key) DO NOTHING`; no SELECT-then-INSERT race, a Saga replay is a
  no-op.
- **Single-writer Drawing state** — `OPEN → CLOSED | VOID`; terminal states reject re-selection (a
  duplicated scheduler fire is a no-op), backed by a pessimistic `SELECT … FOR UPDATE`.
- **Tamper-evident audit** — every BEP write is SHA-256 hash-chained + DB-immutable.
- **No PII** — entries reference `memberId` only.

## Slice status

| Slice | What | Tests |
|---|---|---|
| 1 | `CampaignStatus` / `DrawingStatus` transition matrices | `CampaignStatusTest` (7), `DrawingStatusTest` (4) |
| 2 | Winner Selection algorithm (SEEDED_RNG / WEIGHTED / FIRST_N) | `WinnerSelectionTest` (9) |
| 3 | Entities + repositories | (compile) |
| 4 | Campaign CRUD + LIVE approval gate | `CampaignServiceTest` (6) |
| 5 | Entry Service + Winner Selection Service + Drawing Scheduler | covered by IT |
| 6 | Outbox + audit + events | covered by IT |
| 7 | REST API + DTOs + ProblemAdvice | covered by IT |
| 8 | Integration test | `CampaignIntegrationTest` (7) |

**33 tests** — 26 unit (TDD, tests-first for pure logic) + 7 Testcontainers IT (Postgres + Kafka). No
WireMock: campaign has no synchronous downstream dependency.

## Build & run

```bash
./gradlew test          # 26 unit + 7 Testcontainers IT (Postgres + Kafka)
./gradlew bootRun       # needs Postgres + Kafka
```

Requires a **JDK 25** toolchain. Flyway owns the schema (`ddl-auto: validate`). The Outbox Relay and the
Drawing Scheduler run in-pod under ShedLock (one pod per tick). See
[`DETAILED-DESIGN.md`](DETAILED-DESIGN.md) for the state machines, the selection algorithm, the T-13
surface, and documented divergences.
