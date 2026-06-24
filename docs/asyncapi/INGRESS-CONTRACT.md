# Loyalty Integration Contract — Producer Obligations

> **What this is.** The **normative, bank-agnostic** set of obligations that **any Host Bank's bank-side adapter MUST satisfy** to integrate with the Rochallor Loyalty Platform. It is part of the **Loyalty Integration Contract** and is **owned by Loyalty**. Hand this page to a Host Bank's integration team together with the two machine-readable specs below.
>
> **What this is NOT.** It is **not** a bank-specific mapping. How a bank's *native* events (e.g. HBP's `ph.transaction.transaction-event`, with its 27 `transactionType` values and step/status lifecycle) map onto this contract is the **bank's own concern**, authored and owned by the bank-side adapter — which lives **outside this repository**. Loyalty neither ships nor reviews that adapter.

---

## The contract (what you implement against)

| Artifact | Surface | What it defines |
|---|---|---|
| [`loyalty-ingress.yaml`](loyalty-ingress.yaml) | Kafka (produce) | The `loyalty.ingress.*` channels + JSON Schemas your adapter produces to. |
| [`../openapi/loyalty-integration-bridge-webhooks.yaml`](../openapi/loyalty-integration-bridge-webhooks.yaml) | HTTPS (POST) | The voucher partner webhook (`POST /webhooks/voucher`). |

The platform's `loyalty-integration-bridge` validates every inbound event against the bundled JSON Schema; **a non-conforming event is routed to a per-channel DLQ, not processed**.

## Ownership boundary

| Loyalty owns (this contract) | The Host Bank owns (its adapter, out of repo) |
|---|---|
| The `loyalty.ingress.*` channel set, JSON Schemas, and canonical vocabularies (`paymentType`, `lifecycleType`, …). | Mapping the bank's **native** event vocabulary → this contract. |
| The obligations on this page. | Resolving `accountNumber → customerId` (CIF) via the bank's own lookup. |
| `paymentType → earn_source` routing (bank-uniform, internal to the Bridge). | De-duplicating native multi-step events down to one settled event. |

## Producer obligations (normative — every adapter MUST)

1. **One event per completed business activity.** If the native system emits a per-step lifecycle, the adapter emits **exactly once**, at the **settled/completed** outcome (e.g. `step=COMPLETED, status=SUCCESS`). Authorisations, intermediate steps, and pending states are **not** earnable (no pre-settle).
2. **`customerId` is required on every event.** It is the bank's CIF / customer identifier. The platform resolves Member(s) and Program(s) downstream; it has no other way to attribute the earn. An event without `customerId` is a contract violation.
3. **Deterministic, stable `eventId`.** The same business activity MUST always yield the **same** `eventId` (e.g. derived from the native transaction id). This is the platform's idempotency key — it makes step de-duplication and adapter retries safe (downstream dedups on it).
4. **Customer-scoped only.** Never send `memberId` or `programId` — the adapter does not know them. Partition/key by `customerId` (the voucher webhook is keyed by `jobHandle`).
5. **Canonical vocabulary only.** Discriminators such as `paymentType` MUST be a value from the contract's closed enum. A value outside the enum is a contract violation (→ DLQ); map anything you can't classify to **`OTHER`**.
6. **Propagate `traceparent`.** Set the W3C `traceparent` header from the originating banking transaction so one distributed trace spans bank → Bridge → earning → core. If none exists natively, synthesise one per activity.
7. **Reversal lineage.** On `loyalty.ingress.reversal.v1`, `originalEventId` MUST equal the `eventId` of the original earn event being reversed — that is how `loyalty-core` finds and compensates the right ledger entries by `source_ref`.
8. **No PII.** Send `customerId` only — never PAN, card last-4, name, phone, or national ID. Drop such fields before producing.
9. **Currency from the customer's debited perspective.** Send the debited `currency` (`USD` or `VND`); the platform performs **no FX normalisation**.
10. **JSON, conforming to the schema.** `application/json`; conform to the channel's JSON Schema. Tolerant reader applies — extra fields are ignored, but all `required` fields must be present and well-typed.

## Webhook-specific obligations (voucher partner)

- Sign `HMAC-SHA256(sharedSecret, "<X-Timestamp>.<raw-body>")`, hex-encoded in `X-Signature`; send the matching `X-Timestamp` (epoch seconds). Requests outside the replay window or with a bad signature are rejected `401` (threat DD-2).
- `jobHandle` MUST match the `redemption_saga.job_handle` the platform issued; `status ∈ {READY, FAILED}`.

## Channel quick-reference

| Produce to | When | Canonical result |
|---|---|---|
| `loyalty.ingress.card_spend.v1` | A settled card purchase | Earn, `source=CARD_SPEND` |
| `loyalty.ingress.payment.v1` | A completed non-card payment (bill / transfer / P2P / QR / top-up) | Earn, `source` from `paymentType` |
| `loyalty.ingress.reversal.v1` | A reversal/refund of a prior earn | Core-driven clawback |
| `loyalty.ingress.customer_lifecycle.v1` | A customer-lifecycle change (v1: closed) | Member lifecycle |
| `loyalty.ingress.term_deposit.v1` | A term deposit opened | Earn, `source=TERM_DEPOSIT_OPENED` |
| `POST /webhooks/voucher` | 3rd-party voucher fulfilment outcome | Redemption saga resume |

## Versioning

Channels are versioned (`.v1`). Within a version, evolution is **additive** and consumers are tolerant readers; a **breaking** change ships as a new `.v<n>` channel and you migrate deliberately. The platform announces new versions; old ones keep working until sunset.
