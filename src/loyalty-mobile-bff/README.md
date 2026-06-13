# loyalty-mobile-bff

The **customer-facing edge API** — turns the RDB Mobile App's calls into composed reads/writes across
the loyalty backend ([C4 L2 BFFs](../../docs/c4/level-2-containers.md);
[`loyalty-mobile-bff.yaml`](../../docs/openapi/loyalty-mobile-bff.yaml)).

Behind AWS API Gateway (route `/api/loyalty/mobile/*`) it **aggregates only** — it owns no datastore and
produces/consumes no Kafka. It fans out over REST to:

| Upstream | What the BFF uses it for |
|---|---|
| `loyalty-core` | enrolled programs, opt-in/out + T&Cs, balance, transaction history, tier progress, expiring cohorts |
| `loyalty-redemption` | eligible reward catalogue, reward detail, two-phase redemption (submit + poll) |
| `loyalty-campaign` | live campaigns, the member's sweepstakes entries + winner status |

> **New here?** Read [`DETAILED-DESIGN.md`](DETAILED-DESIGN.md) — a self-contained design + user guide
> (the `customerId` edge identity, the read aggregation pattern, the sync/async redemption flow,
> campaigns, error translation) with sequence diagrams.

## Invariants it upholds

- **The edge is keyed by `customerId`; the BFF does no token handling.** The gateway **Authentication
  Service** (a Lambda) verifies the customer token and injects the `customerId` (the Host Bank CIF) into
  the request before it reaches the BFF — the BFF never sees or decodes a JWT. `customerId` arrives as a
  `?customerId=` query param (reads + `opt-out`) or in the request body (`opt-in`, `tcs-acceptance`,
  `redeem`). Controllers read it via `@RequestParam long customerId` or `req.customerId()`; a missing
  required `customerId` is a normal `400`, not a BFF `401`. The edge **never** uses Loyalty's internal
  `memberId`; the BFF maps `customerId → memberId` (v1 1:1) only when it calls the internal services.
- **Thin aggregation, Anti-Corruption clients.** Each controller method delegates to a per-upstream
  `RestClient`; the BFF is a *client* of these services, never a sharer of their data. Pinned to HTTP/1.1.
- **Upstream errors are translated, not leaked.** `UpstreamErrorHandler` maps an upstream 4xx/5xx into a
  `BffException` preserving the upstream status and its RFC-7807 `code` (e.g. `INSUFFICIENT_BALANCE`,
  `STEP_UP_REQUIRED`), so the edge surfaces the real cause instead of a blanket 500.
- **Redemption status is mirrored and mapped.** `POST /redemptions` (body
  `{customerId, accountNumber, programId, rewardId}`) forwards `customerId` (CIF) + `accountNumber` (the
  CASA for a cashback credit) + the `Idempotency-Key` + optional `X-Step-Up-Token` verbatim, and mirrors
  redemption's **200 (sync)** / **202 (async)** to the edge; the Saga status is mapped to the customer
  enum (`COMMITTED → COMPLETED`, `RELEASED → FAILED`). The Host Bank Payment Hub is addressed by CIF +
  account, never by `memberId`.
- **Multi-Program from v1.** Every Points-bearing payload carries `programId` + `programCode`; program
  paths take `{programId}`.

## Slice status

| Slice | Status |
|---|---|
| Edge keyed by `customerId` (query param / body); internal `customerId → memberId` map (v1 1:1) | ✅ scaffolded |
| Programs (list enrolled, opt-in/out, T&Cs acceptance) → core | ✅ scaffolded |
| Balance & History (balance, transactions, tier, expiring points) → core | ✅ scaffolded |
| Rewards & Redemption (eligible catalogue, detail, submit/poll, 200/202 mirror) → redemption | ✅ scaffolded |
| Campaigns (live campaigns, my drawing entries) → campaign | ✅ scaffolded |
| Upstream-error translation → RFC-7807 Problem at the edge | ✅ scaffolded |

**Deferred from this scaffold (matches platform deferrals):** all customer-token handling lives upstream
in the gateway **Authentication Service** (token verify + `customerId` injection) — the BFF does none of
it; the OpenAPI `customerJwt` bearer scheme is kept as edge-level documentation (a token is still
required at the gateway) but the BFF itself does not process it. Also: the **ElastiCache Redis**
read-through cache for balance/tier/catalogue projections (Arch §4.2.2 — wired straight through to core
for now); the **Customer Service** display-name fetch (T-06); **Keycloak** step-up challenge/verify
(the token is forwarded to redemption, not minted here); mTLS wiring (cluster infra).

> **Upstream dependency note.** Several customer reads (`/members/{id}/programs`, …/`balance`,
> …/`transactions`, …/`tier`, …/`expiring-points`, …/`opt-in|opt-out|tcs-acceptance`) are the
> member-scoped projections this BFF needs from `loyalty-core`; they **extend core's v1 internal API**
> (which today publishes the Reservation API + a slim projection). They are stubbed in the IT and called
> by [`CoreClient`](src/main/java/com/loyalty/mobilebff/client/CoreClient.java) — the BFF defines what it
> needs from its upstreams.

## Build & run

Stack: **Java 25** · **Spring Boot 4.x** · **Spring MVC (RestClient)** · **Gradle (Kotlin DSL)** —
synced with the backend services. No JPA / Flyway / Kafka (aggregation only).

```bash
./gradlew test     # 7 WireMock-backed integration tests (all three upstreams stubbed) — no Docker needed
./gradlew bootRun  # needs core ($CORE_BASE_URL), redemption ($REDEMPTION_BASE_URL), campaign ($CAMPAIGN_BASE_URL)
```

> Requires a **JDK 25** toolchain. The IT stubs core + redemption + campaign with a single WireMock
> server, so no sibling service is needed to run it.
