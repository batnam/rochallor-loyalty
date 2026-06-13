# Rochallor Loyalty Platform — Test Pyramid Plan

> **Artifact §11.4** of [`enterprise-architect.md`](../enterprise-architect.md#114-supporting-artifacts-to-build).
> The verification strategy across **unit / contract (OpenAPI + AsyncAPI) / integration / E2E / load**. It consumes the contracts in [`docs/openapi/`](openapi/) and [`docs/asyncapi/`](asyncapi/), the schema in [`data-catalogue.md` §10](data-catalogue.md#10-embedded-ddl-full-sql), the critical flows in [§4.6](../enterprise-architect.md#46-critical-sequence-flows), and the abuse cases in [`docs/threat-model.md`](threat-model.md).

---

## 1. Principles

1. **The Ledger invariant is sacred.** `balance = Σ(deltas)` per `(member, program)` (P5) is asserted as a **property** at every layer that touches Points, not just an example case.
2. **Money-equivalent paths get the deepest coverage.** Earn, redeem, adjust, and clawback carry the most tests and the only mandatory mutation/property tests.
3. **Test against real infrastructure below the contract line.** Integration tests run on **real PostgreSQL and real Kafka via Testcontainers** — never an in-memory DB or mocked broker. The DB CHECK constraints, the `trg_point_ledger_immutable` trigger, and the `UNIQUE(source_ref, entry_type)` idempotency are correctness controls and must be exercised as deployed.
4. **The contract is the boundary.** Every published REST/event message is verified against its committed OpenAPI/AsyncAPI schema; every consumer is verified against the schema it depends on. Schemas are versioned; backward-compat is a CI gate (§5).
5. **Determinism where the spec promises it.** The DSL interpreter and the seeded-RNG winner selection are pure/deterministic (campaign L3) and are tested as pure functions with golden vectors.
6. **Shape:** ~70% unit, ~20% contract+integration, ~10% E2E; load/security run on their own cadences, not in the per-commit pyramid.

```
        ╱╲          E2E (5 critical flows §4.6)            ~5%   ephemeral env, nightly
       ╱──╲         Integration (Testcontainers: PG+Kafka) ~15%  per-service, per-PR
      ╱────╲        Contract (OpenAPI + AsyncAPI)           ~10%  per-PR + compat gate
     ╱──────╲       Unit (pure logic, broad)               ~70%  per-PR, fast
    ╱────────╲
   Load + Security + Chaos + DR  →  scheduled (nightly / pre-prod / quarterly)
```

---

## 2. Layer 1 — Unit (pure logic)

Fast, no I/O. The highest-value targets are the pure, money-affecting algorithms:

| Component | Service | What to assert |
|---|---|---|
| **DSL interpreter** | earning | Each primitive (condition, earn-formula, cap) against golden in/out vectors; malformed DSL rejected at parse; no side effects |
| **Rule evaluation → deltas** | earning | Correct qualifying/redeemable deltas; multiplier/tier interaction; cap arithmetic |
| **Per-producer Translators** | bridge | `(vendorEvent) → canonicalEvent` field mapping; pure, no I/O (bridge L3) — one suite per translator |
| **FIFO cohort consumption** | core | Oldest cohort first; partial consumption; `consumed + expired ≤ original` |
| **Tier evaluation** | core | Threshold crossing up; no downgrade on redeem; qualifying-metric windows |
| **Saga state machine** | redemption | Legal transitions only (`PENDING_RESERVATION→RESERVED→FULFILLING→COMMITTED/REJECTED/RELEASED`) |
| **Seeded-RNG winner selection** | campaign | Deterministic given `(seed, ordered entries)`; reproduces `winner_record.winner_index` |
| **Expiry date snapshot** | core | `expires_at = earn_ts + program/tier expiry`; tier override |

---

## 3. Layer 2 — Contract

### 3.1 REST (OpenAPI)
Both BFF specs ([`openapi/`](openapi/)) are the source of truth.
- **Provider verification:** each BFF's controller responses validate against its own OpenAPI schema (request/response/status). Recommended: **Spring Cloud Contract** (provider-side, JVM-native) or **Schemathesis/Dredd** driving the spec against a running BFF.
- **Consumer-driven contracts:** the Mobile Banking App and BEP publish consumer expectations (Pact-style); the BFFs verify against them so a breaking change fails the producer's PR, not the consumer's release.
- **Edge auth contracts:** unauthenticated → `401`; wrong role/Program scope → `403` (admin); `Idempotency-Key` required on `POST /redemptions`; high-value redeem without step-up → `403 STEP_UP_REQUIRED`.

### 3.2 Async (AsyncAPI)
The five published specs + `external-consumed.yaml` ([`asyncapi/`](asyncapi/)) are the source of truth.
- **Producer-side:** every emitted message is asserted against its AsyncAPI message schema before the test passes (envelope `{eventId, eventType, occurredAt, programId, schemaVersion}` present; partition key correct).
- **Consumer-side:** each consumer is fed schema-valid and schema-invalid samples; invalid external messages must land in the **DLQ**, not propagate (bridge L3).
- **Tooling:** **Microcks** (mocks + contract-tests both OpenAPI and AsyncAPI) is recommended as the single contract harness; alternatively per-repo JSON-Schema assertions + the CI schema-diff gate (§5).

---

## 4. Layer 3 — Integration (Testcontainers: real PG + Kafka)

Per service, against its real RDS schema (apply the service's migration from [`data-catalogue.md` §10](data-catalogue.md#10-embedded-ddl-full-sql)) and a real Kafka broker.

| Scenario | Service | Asserts |
|---|---|---|
| **Idempotent earn** | core | Re-applying the same `(source_ref, entry_type)` is a silent no-op (UNIQUE) — balance unchanged |
| **Ledger immutability** | core | `UPDATE`/`DELETE` on `point_ledger` raises (`trg_point_ledger_immutable`) |
| **Approval-gated adjustment** | core | `Adjusted` entry only written via `confirm` on an `APPLIED` `approval_request`; entry carries `approval_request_id` + `bep_approval_ref` (4-eyes is BEP's) |
| **Two-phase redemption** | core+redemption | reserve→commit writes one `Redeemed`; reserve→release returns points; TTL sweeper releases stale `HELD` |
| **Inventory atomicity** | redemption | Concurrent reserves on 1-stock reward → exactly one wins, no oversell |
| **Outbox relay** | all | Business write + outbox insert in one txn; relay drains to Kafka; rows marked `SENT` |
| **Expiry job** | core | Past-expiry cohorts write `Expired` entries; advisory lock prevents double-fire |
| **ShedLock** | all w/ jobs | Multi-pod: exactly one pod runs a `@Scheduled` tick |
| **Balance projection** | core | Projection equals `Σ(deltas)` after a mixed sequence (earn/redeem/expire/reverse/adjust) — **property test** |
| **Negative balance** | core | Clawback drives `redeemable_balance < 0`; redemption then blocked |
| **Multi-program broadcast** | earning | One earn event fans out to N programs; per-program caps independent |

---

## 5. Schema-evolution gate (cross-cutting CI)

Backward-compatibility is enforced, not hoped for (§4.2.4):
- **Kafka:** there is **no schema registry** (plain MSK); every `loyalty.*` JSON-Schema change runs a **CI schema-diff** against the previous committed version (additive/backward-compatible only) — incompatible change must bump the topic version (`.v2`) or the build fails. Backed by **tolerant-reader** consumers.
- **REST:** OpenAPI diff gate (e.g. `oasdiff`) flags breaking changes to a published BFF contract; breaking change requires an API version bump + coordinated consumer release.

---

## 6. Layer 4 — End-to-end (the five critical flows)

Run in an ephemeral environment (all 7 services + PG + Kafka, partner + Payment Hub stubbed). One scenario per [§4.6](../enterprise-architect.md#46-critical-sequence-flows) flow:

| E2E | Flow | Oracle |
|---|---|---|
| **E2E-1** | Earn happy path (§4.6.1) | `paymenthub.card_spend.v1` in → `Earned` ledger entries + `loyalty.earning.points_earned.v1` out; balance increases |
| **E2E-2** | Redeem cashback sync (§4.6.2) | `POST /redemptions` → `200 COMPLETED`; `Redeemed` entry; CASA disburse stub called once |
| **E2E-3** | Redeem 3rd-party voucher async (§4.6.3) | `202 PENDING`; partner webhook → `loyalty.fulfillment.resume.v1` → `COMPLETED`; TTL-expiry path → `RELEASED` + points returned |
| **E2E-4** | Refund clawback (§4.6.4) | `paymenthub.payment_reversed.v1` → compensating `Reversed` entries; balance may go negative; redeem blocked |
| **E2E-5** | Sweepstakes (§4.6.5) | redeem entry → `drawing_entry`; scheduled draw → `winner_record` + `drawing_completed`; **re-verify winner from `seed_hex`** |
| **E2E-6** | Maker-checker (§4.6.6) | maker `POST /adjustments` → PENDING; checker approve → `Adjusted` entry with both ids; checker==maker rejected |

---

## 7. Layer 5 — Load & performance (against the NFRs)

Recommended tool: **k6** or **Gatling**. Targets from §4.6 / §7.2:

| Test | Target SLA | Notes |
|---|---|---|
| **Earn throughput** | ~100 events/sec steady; ~150 rule-evals/sec at avg N=1.5 programs | Sustained + 5× spike |
| **Earn latency** | `CardSpendPosted` → `PointsEarned` **p95 < 10s, p99 < 30s** (§4.6.1) | Measured end-to-end through the Bridge |
| **Redeem sync latency** | reserve()+commit() **p95 < 1.5s** (§4.6.2) | Cashback adapter |
| **Consumer lag** | earn-consumer lag **< 60s** under load (§7.2) | Alert threshold = test threshold |
| **Fulfillment error budget** | adapter error rate **< 1% / 5 min** (§7.2) | Inject partner latency/failure |
| **Soak** | 99.95% availability profile (P9) | Multi-hour; watch projection drift |

---

## 8. Cross-cutting test types

- **Security / abuse tests (from [`threat-model.md`](threat-model.md)):** replayed earn event is a no-op (T-02); forged event to a topic the producer lacks ACL for is rejected (T-03/R6 cross-tenant isolation test in non-prod); step-up bypass blocked (T-10); a forged/replayed BEP `confirm` is rejected — mTLS + BEP assertion, idempotent (T-22); poison message → DLQ, consumer survives (T-20); duplicate sweepstakes entry capped (T-13).
- **Graceful degradation (P9):** with the Loyalty BFF down, the Mobile App shows cached balance + "last updated", suppresses Redeem CTA, and **never blocks a critical-path flow** — an integration test from the Mobile-team side, contract-enforced (R4).
- **Chaos:** kill a pod mid-`@Scheduled` run (graceful-shutdown ≥ longest job); broker partition; RDS failover.
- **DR drill:** quarterly restore-and-verify in `ap-southeast-2`; assert RPO ≤ 15 min, RTO ≤ 60 min (§7.3).

---

## 9. Per-service coverage matrix

| Service | Unit | Contract | Integration | E2E |
|---|---|---|---|---|
| **loyalty-core** | tier, FIFO, expiry, projection | core REST (reserve/commit/release, earn), confirm | ledger invariants, immutability, approval-gated adjustment, outbox, expiry | E2E-1/2/4/6 |
| **loyalty-earning** | DSL, rule deltas, caps | `points_earned`/`points_clawed_back` async; admin rule REST | idempotency, multi-program broadcast | E2E-1/4 |
| **loyalty-redemption** | saga FSM, eligibility | `redemption.*` async; mobile/admin REST | two-phase, inventory, resume | E2E-2/3/5 |
| **loyalty-campaign** | seeded-RNG, entry rules | `campaign.*` async; campaign REST | drawing scheduler, winner persistence | E2E-5 |
| **loyalty-integration-bridge** | translators (pure) | inbound external + canonical async; webhook | DLQ on bad schema, velocity rebuild | E2E-1/3/4 |
| **loyalty-mobile-bff** | aggregation logic | mobile OpenAPI provider + consumer-driven | auth/step-up/idempotency | E2E-2/3/5 |
| **loyalty-admin-bff** | role mapping | admin OpenAPI provider + consumer-driven | role/Program-scope authZ | E2E-6 |

---

## 10. CI/CD gates

| Stage | Runs |
|---|---|
| **Per-PR (fast)** | unit + contract (provider verify against own spec) + service integration (Testcontainers) + schema-compat gate (§5) + PII-in-logs lint (T-17) |
| **On merge to main** | full integration + consumer-driven contract verification + OpenAPI/AsyncAPI lint (`redocly` / `asyncapi validate`) |
| **Nightly (ephemeral env)** | E2E-1…6 + abuse-test suite + chaos subset |
| **Pre-prod / release** | load suite (§7) + cross-tenant ACL isolation (R6) + graceful-degradation (R4) |
| **Quarterly** | DR restore-and-verify (§7.3) |

---

## 11. Test data & environments

- **Seed Program:** the v1 seed Program (id=1; deployment-supplied name, e.g. `RDB_REWARDS` for the HBP deployment) with a known tier ladder + a small canonical rule/reward set (reuse the planned **Sample DSL library**, §11.4).
- **Synthetic members** keyed by synthetic `customerId`; **never** production PII (P1).
- **Stubs:** Payment Hub (event producer + disburse), 3rd-party voucher partner (provision + webhook), Authentication Service (JWT mint + tx-PIN), notification-service (sink). Recommended: WireMock for REST stubs, a test producer for Kafka, Microcks for spec-driven mocks.
- **Determinism:** fixed clock and fixed RNG seed in E2E so expiry windows and winner selection are reproducible.

---

## 12. Open items

- **OI-1:** Choose one contract harness — **Microcks** (spec-driven, covers both OpenAPI+AsyncAPI) vs **Pact + Spring Cloud Contract** (consumer-driven, JVM-native). Recommend Microcks for breadth; revisit if consumer-driven workflow is preferred by the Mobile/BEP teams.
- **OI-2:** *Resolved* — Kafka uses **JSON Schema, no registry**; the compat gate is a CI schema-diff (above), not a registry call.
- **OI-3:** Coverage *thresholds* (line/branch %) per service to be set with the build pipeline; this plan sets the *shape*, not numeric gates.
