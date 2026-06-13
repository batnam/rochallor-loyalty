# loyalty-integration-bridge

The **ingress gateway** for the Loyalty platform.

A Host Bank's adapter produces **ingress events** that conform to the Loyalty-authored contract
([`docs/asyncapi/loyalty-ingress.yaml`](../../docs/asyncapi/loyalty-ingress.yaml)). This service:

1. **consumes** a `loyalty.ingress.*` channel,
2. **validates** it against its bundled JSON Schema (`src/main/resources/schema/`) — invalid → per-channel DLQ,
3. **stamps** the canonical `source` (`earn_source_code`),
4. **produces** the internal canonical event `loyalty.earn.translated.v1` (customer-scoped — no `memberId`/`programId`; member + program resolution happens in `loyalty-earning`).

Stateless, no RDS. Idempotent producer; downstream dedups by `eventId`.

> **New here?** Read [`DETAILED-DESIGN.md`](DETAILED-DESIGN.md) — a self-contained design + user guide
> (concepts, context, per-flow sequence diagrams, data/config reference, run & operate). You should not
> need to read the code to understand the service.

## Slice status

| Channel | Status |
|---|---|
| `loyalty.ingress.card_spend.v1` → `loyalty.earn.translated.v1` | ✅ scaffolded (constant `source=CARD_SPEND`) |
| `loyalty.ingress.payment.v1` → `loyalty.earn.translated.v1` | ✅ scaffolded (canonical `paymentType` → `earn_source`, bank-uniform map; `paymentType` preserved for DSL) |
| `loyalty.ingress.reversal.v1` → `loyalty.payment.reversed.v1` | ✅ scaffolded (clawback core-driven) |
| `loyalty.ingress.customer_lifecycle.v1` → `loyalty.member.lifecycle.v1` | ✅ scaffolded |
| Velocity Anomaly consumer → `loyalty.fraud.alert.v1` | ✅ scaffolded (sliding window per customerId, hysteresis; degraded-availability) |
| Voucher webhook (HTTPS + HMAC) → `loyalty.fulfillment.resume.v1` | ✅ scaffolded (`POST /webhooks/voucher`, HMAC-SHA256 + timestamp window, DD-2) |

## Build & run

Stack: **Java 25** · **Spring Boot 4.x** · **Spring Kafka** · **Gradle (Kotlin DSL)**.

```bash
./gradlew test          # unit tests (CardSpendTranslatorTest)
./gradlew bootRun       # needs a Kafka broker at $KAFKA_BOOTSTRAP_SERVERS (default localhost:9092)
```

> Requires a **JDK 25** toolchain. The Gradle build pins `languageVersion = 25`; install JDK 25
> (or configure Gradle toolchain auto-provisioning) — a lower local JDK will not satisfy it.
