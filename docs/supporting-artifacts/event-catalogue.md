# Rochallor Loyalty Platform — Event Catalogue

> **Artifact §11.4** of [`enterprise-architect.md`](../enterprise-architect.md#114-supporting-artifacts-to-build).
> The Event Backbone in [§4.2.4](../enterprise-architect.md#424-event-backbone-async) is the *topology*; this catalogue is the **per-channel contract**: topic, message, producer, consumers, key, and schema. Machine-readable AsyncAPI 2.6.0 specs are under [`docs/asyncapi/`](asyncapi/).

---

## 1. Scope

Loyalty is an **event-driven satellite** of the Host Bank Platform: it consumes producer-emitted domain events (not CDC) and publishes its own Loyalty Domain Events for downstream consumers. All async transport is the **shared MSK Kafka** cluster with topic-level ACLs.

| AsyncAPI spec | Application | Role |
|---|---|---|
| [`asyncapi/loyalty-core.yaml`](../asyncapi/loyalty-core.yaml) | `loyalty-core` | Publishes member + ledger events |
| [`asyncapi/loyalty-earning.yaml`](../asyncapi/loyalty-earning.yaml) | `loyalty-earning` | Publishes PointsEarned (clawback is core-driven) |
| [`asyncapi/loyalty-redemption.yaml`](../asyncapi/loyalty-redemption.yaml) | `loyalty-redemption` | Publishes redemption events |
| [`asyncapi/loyalty-campaign.yaml`](../asyncapi/loyalty-campaign.yaml) | `loyalty-campaign` | Publishes campaign/drawing events |
| [`asyncapi/loyalty-integration-bridge.yaml`](../asyncapi/loyalty-integration-bridge.yaml) | `loyalty-integration-bridge` | Publishes canonical translations + cross-cutting events |
| [`asyncapi/external-consumed.yaml`](../asyncapi/external-consumed.yaml) | *(upstream HBP)* | Reference for events Loyalty consumes but does not own |

> **AsyncAPI 2.x semantics.** §11.4 specifies AsyncAPI **2.x**, whose operations are client-centric: a `subscribe` operation in a spec means *"this application emits the message; others subscribe to receive it."* Each per-service spec therefore lists the channels that service **publishes**. The authoritative producer→consumer mapping is the matrix in [§3](#3-channel-inventory) (AsyncAPI 2.x cannot express it cleanly in one document).

---

## 2. Conventions

> Naming, envelope, partition key, and idempotency are normative; serialization & schema-evolution follow the JSON-Schema-on-MSK convention. `.v<n>` is the compatibility boundary (breaking → new topic); the envelope `schemaVersion` is the self-describing in-payload revision.

| Concern | Decision |
|---|---|
| **Topic naming** | `loyalty.<context>.<event_type>.v<n>` — snake_case event type, `<context>` = producing bounded context. Schema-version bump = new topic suffix (`.v2`). |
| **Envelope** | Member-scoped (post-resolution) `loyalty.*` events carry `{eventId, eventType, occurredAt, programId, schemaVersion}`; `programId` is present from v1 even with one Program seeded. **Customer-scoped Bridge-emitted events** (`loyalty.ingress.*`, `loyalty.earn.translated.v1`, `loyalty.payment.reversed.v1`, `loyalty.member.lifecycle.v1`, `loyalty.fraud.alert.v1`) omit `programId` — the Bridge resolves no Program; `loyalty-earning` adds it on fan-out. |
| **Partition key** | `memberId` for member-scoped events (preserves per-Member ordering); `customerId` for **all Bridge-emitted customer-scoped events** (`loyalty.ingress.*`, `loyalty.earn.translated.v1`, `loyalty.payment.reversed.v1`, `loyalty.member.lifecycle.v1`, `loyalty.fraud.alert.v1` — no Member resolved yet); `drawingId` for `drawing_completed`/`drawing_void`; `jobHandle` for `fulfillment.resume`. |
| **Idempotency** | `eventId` is the consumer-side dedup key. The Ledger additionally dedups by `UNIQUE(source_ref, entry_type)`. |
| **Tracing** | Every event carries a `traceparent` header; the bank-side adapter sets it on the ingress event and the Bridge propagates it across the seam so one trace spans bank adapter → Bridge → earning → core → notification (integration-bridge L3 §3.2). |
| **Delivery** | Producers use the transactional **outbox** (per-service `outbox` table) drained by an Outbox Relay; the Bridge uses an idempotent Kafka producer with no outbox (it has no business write). "Effectively exactly-once" = idempotent produce + `eventId` dedup, no 2PC. |
| **Schema format** | **JSON Schema, no schema registry** (plain MSK) — the AsyncAPI specs are the single source of truth; **backward-compatibility enforced by a CI schema-diff gate** + tolerant readers. |
| **Bad input** | The Bridge schema-validates every inbound external message; failures go to a **per-topic DLQ**, never onward (integration-bridge L3 §3.2). |

### Sensitivity

All topics are loyalty-operational (financial-adjacent) — none carry PII. Payloads reference `customerId`/`memberId` only; never name/phone/NRC ([§5.3](../enterprise-architect.md#53-pii--data-protection)). `loyalty.fraud.alert.v1` is the most sensitive (fraud signal) and is restricted by ACL to `loyalty-admin-bff`.

---

## 3. Channel inventory

### 3.1 Loyalty-owned (published)

| Topic | Message | Producer | Consumers | Key |
|---|---|---|---|---|
| `loyalty.earn.translated.v1` | EarnEvent | integration-bridge | loyalty-earning; Velocity Anomaly (bridge) | customerId |
| `loyalty.earning.points_earned.v1` | PointsEarned | loyalty-earning | notification-service | memberId |
| `loyalty.ledger.points_clawed_back.v1` | PointsClawedBack | loyalty-core | notification-service | memberId |
| `loyalty.payment.reversed.v1` | ReversalEvent | integration-bridge | loyalty-core (Reversal Consumer) | customerId |
| `loyalty.member.opted_in.v1` | MemberOptedIn | loyalty-core | notification-service | memberId |
| `loyalty.member.tier_changed.v1` | TierChanged | loyalty-core | notification-service | memberId |
| `loyalty.member.tier_at_risk.v1` | TierAtRisk | loyalty-core | notification-service | memberId |
| `loyalty.member.lifecycle.v1` | MemberLifecycle | integration-bridge | loyalty-core | customerId |
| `loyalty.ledger.points_expiring_soon.v1` | PointsExpiringSoon | loyalty-core | notification-service | memberId |
| `loyalty.ledger.manual_adjustment_applied.v1` | ManualAdjustmentApplied | loyalty-core | notification-service (configurable) | memberId |
| `loyalty.redemption.completed.v1` | RedemptionCompleted | loyalty-redemption | notification-service | memberId |
| `loyalty.redemption.failed.v1` | RedemptionFailed | loyalty-redemption | notification-service | memberId |
| `loyalty.campaign.drawing_completed.v1` | DrawingCompleted | loyalty-campaign | notification-service | drawingId |
| `loyalty.campaign.winner_selected.v1` | WinnerSelected | loyalty-campaign | notification-service | memberId |
| `loyalty.campaign.drawing_void.v1` | DrawingVoid | loyalty-campaign | notification-service | drawingId |
| `loyalty.fraud.alert.v1` | FraudAlert | integration-bridge | loyalty-admin-bff (Fraud Ops) | customerId |
| `loyalty.fulfillment.resume.v1` | FulfillmentResume | integration-bridge | loyalty-redemption (Resume Consumer) | jobHandle |

### 3.2 Inbound to the Bridge — the Loyalty-authored ingress contract

The Bridge consumes **Loyalty-authored** `loyalty.ingress.*` channels, each produced by the Host Bank's own **bank-side adapter** (outside this repo). The Bridge validates against the bundled JSON Schema, stamps the canonical `source`, and re-emits the §3.1 canonical events. A bank's *native* topics (e.g. HBP's `ph.transaction.transaction-event`) are the adapter's **input** — per-deployment, never consumed by Loyalty.

| Ingress channel (Bridge consumes) | Produced by | Bridge re-emits | Key |
|---|---|---|---|
| `loyalty.ingress.card_spend.v1` | bank-side adapter | `loyalty.earn.translated.v1` (source `CARD_SPEND`) | customerId |
| `loyalty.ingress.payment.v1` | bank-side adapter | `loyalty.earn.translated.v1` (source from canonical `paymentType`) | customerId |
| `loyalty.ingress.reversal.v1` | bank-side adapter | `loyalty.payment.reversed.v1` | customerId |
| `loyalty.ingress.customer_lifecycle.v1` | bank-side adapter | `loyalty.member.lifecycle.v1` | customerId |

`loyalty.ingress.payment.v1` carries a canonical `paymentType` (`BILL_PAYMENT` / `FUND_TRANSFER` / `P2P_TRANSFER` / `QR_PAYMENT` / `TOPUP` / `OTHER`). The Bridge routes it to an Earn Source via a Loyalty-owned, **bank-uniform** `paymentType → earn_source` map (Bridge service config, not a per-bank file) and preserves `paymentType` on the payload so DSL rules under `FUND_TRANSFER` can discriminate `P2P_TRANSFER` (typically excluded) from `QR_PAYMENT`. Contract + producer obligations: [`INGRESS-CONTRACT.md`](../asyncapi/INGRESS-CONTRACT.md).

`TERM_DEPOSIT_OPENED` (and state-derived account events) remain deferred from v1; when activated they arrive via their own `loyalty.ingress.*` channel like the others.

---

## 4. Event lineage (the five critical flows)

- **Earn** ([§4.6.1](../enterprise-architect.md#461-earning--happy-path-card-spend)): `paymenthub.card_spend.v1` → `loyalty.earn.translated.v1` → *(sync `appendEntry` to core Ledger)* → `loyalty.earning.points_earned.v1` → notify.
- **Redeem sync/async** ([§4.6.2](../enterprise-architect.md#462-redemption--cashback-to-casa-sync)/[§4.6.3](../enterprise-architect.md#463-redemption--3rd-party-voucher-async)): redemption API → (async: partner webhook → `loyalty.fulfillment.resume.v1`) → `loyalty.redemption.completed.v1` | `loyalty.redemption.failed.v1` → notify.
- **Clawback** ([§4.6.4](../enterprise-architect.md#464-refund-clawback-paymentreversed), core-driven): `paymenthub.payment_reversed.v1` → Bridge translates → `loyalty.payment.reversed.v1` → `loyalty-core` reverses entries by `source_ref` → `loyalty.ledger.points_clawed_back.v1` → notify.
- **Sweepstakes** ([§4.6.5](../enterprise-architect.md#465-sweepstakes--entry--drawing)): redemption (entry) → `loyalty.redemption.completed.v1`; at draw time → `loyalty.campaign.drawing_completed.v1` + `loyalty.campaign.winner_selected.v1`; prize fulfillment is a **direct API call** to redemption, not event-driven.
- **Maker-Checker** ([§4.6.6](../enterprise-architect.md#466-maker-checker--manual-point-adjustment)): BEP approval → Ledger `Adjusted` entry → `loyalty.ledger.manual_adjustment_applied.v1` → notify.

The Ledger write on the earn path is a **synchronous `appendEntry` API call** from earning to core (§4.6.1), *not* a Kafka event — `points_earned.v1` is the post-write announcement consumed for notification.

---

## 5. Topic ACLs

Loyalty services **produce only** to `loyalty.*` and **consume** `paymenthub.*`, `corebank.*`, `loyalty.*`, and `customer.*`. ACL configuration is a security-review gate. `loyalty.fraud.alert.v1` is further restricted to the `loyalty-admin-bff` consumer group.

---

## 6. Open items & reconciliation notes

- **R1 — Clawback execution path: RESOLVED.** Decided **core-driven**: the Bridge translates `paymenthub.payment_reversed.v1` → canonical `loyalty.payment.reversed.v1`; `loyalty-core` consumes it and reverses every ledger entry matching the original event's `source_ref`, then emits `loyalty.ledger.points_clawed_back.v1`. The bridge-driven `loyalty.ledger.clawback.v1` channel and the earning-emitted `points_clawed_back` are **dropped**.
- **R2 — `PointsEarned` topic normalized.** The source docs named it three ways: `loyalty.points_earned.v1` (§4.6.1 queue), `loyalty.earning.PointsEarned` (earning L3 outbox), `loyalty.ledger.PointsEarned` (bridge L3 clawback consumer). Canonicalized to **`loyalty.earning.points_earned.v1`** per the stated convention (producer context = earning). Any doc reference to the other two names points here. The lingering `loyalty.points_earned.v1` label in the EA §4.6.1 diagram is corrected.
- **R3 — ACL list omits `customer.*`: RESOLVED.** Added to the consumable-ACL list in the 2026-05-27 amendment.
- **R4 — `corebank.*` modeled as one channel.** The docs enumerate `AccountBalanceSnapshot`, `TermDepositOpened`, "etc." without a fixed list. Modeled as a single `corebank.account_state.v1` channel with a discriminated `eventType`; split per-event when the producer contract firms up.
- **R5 — `WinnerSelected` vs `DrawingCompleted`.** §4.2.4 lists both; §4.6.5 shows only `DrawingCompleted` + a direct prize-fulfillment call. Both channels are catalogued: `drawing_completed` (one per drawing, fan-out) and `winner_selected` (one per winner, carries the auditable seed). Prize fulfillment remains a direct API call, not a `winner_selected` subscription. Multi-winner draws emit one `drawing_completed` + K `winner_selected`.

---

## 7. Validation

Every spec in [`docs/asyncapi/`](asyncapi/) parses as YAML with all local `$ref`s resolving, and validates against the AsyncAPI 2.6.0 schema (`asyncapi validate`). 6 documents, 21 channels total (16 loyalty-owned published + 5 consumed external).
