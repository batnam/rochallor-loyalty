# Loyalty platform — end-to-end test

A black-box, full-flow E2E that runs against the **live `docker compose` stack** (real Postgres,
real Kafka, the actual built service images) with **WireMock** standing in for the two external
systems `loyalty-redemption` calls (Host Bank Payment Hub + 3rd-party voucher partner).

It drives one ordered customer journey and asserts each hop's observable contract (HTTP + Kafka):

```
admin authors + activates a CARD_SPEND rule      (admin-bff + earning)
customer opts in                                  (mobile-bff -> core)
settled card spend produced as an Ingress Event   (Kafka loyalty.ingress.card_spend.v1)
  -> bridge translates                            (assert loyalty.earn.translated.v1)
  -> earning + core award points                  (assert loyalty.ledger.v1, balance, SILVER tier)
CASHBACK redemption (sync)                         (mobile-bff -> redemption -> WireMock Payment Hub -> commit)
THIRD_PARTY_VOUCHER redemption (async)             (FULFILLING -> test fires HMAC webhook -> resume -> commit)
```

## Run it

```bash
cd e2e
python3 -m venv .venv && . .venv/bin/activate
python3 -m pip install -r requirements.txt
python3 -m pytest                       # brings the stack up (build), runs, tears it down
```

Requires Docker + the `docker compose` v2 plugin. The first run builds 7 service images (minutes).

### Knobs (env vars)

| Var | Effect |
|---|---|
| `E2E_ASSUME_RUNNING=1` | Don't build/start/stop the stack; connect to an already-running one. **You must start it with a fresh DB volume yourself** (see "customerId == memberId" below). |
| `E2E_KEEP_STACK=1` | Leave the stack up after the run (for debugging). |

Bring the stack up manually (what the suite does under the hood):

```bash
E2E_WIREMOCK_MAPPINGS="$(pwd)/wiremock/mappings" \
docker compose --project-directory ../src \
  -f ../src/docker-compose.yml -f docker-compose.e2e.yml up -d --build
```

## Layout

- `docker-compose.e2e.yml` — overlay that adds the WireMock container and points
  `loyalty-redemption`'s `PAYMENT_HUB_BASE_URL` / `VOUCHER_PARTNER_BASE_URL` at it. The base
  `src/docker-compose.yml` is left untouched.
- `wiremock/mappings/` — request/response stubs for `POST /disbursements` (cashback) and
  `POST /provision` (voucher). The voucher stub returns a fixed `externalRef`; the test fires the
  resume webhook to the bridge with `jobHandle` == that ref.
- `conftest.py` — stack lifecycle + readiness polling + HTTP/Kafka fixtures.
- `config.py` — endpoints, topics, domain constants.
- `bus.py` — Kafka produce/consume helper (confluent-kafka).
- `test_full_flow.py` — the ordered journey.

## Why some steps hit core directly / a fixed customerId

**`customerId == memberId`.** `loyalty-earning` resolves `customerId -> memberId` via core (the
real IDENTITY id), while `mobile-bff -> redemption` forces `memberId = customerId`. They only agree
when the assigned `memberId` equals the `customerId`, which on a fresh DB means the first opted-in
member must use `customerId = 1`. That's why the suite wipes the volume (`down -v`) each run and
uses `customerId = 1`.

## Findings surfaced while building this E2E

The E2E exists to catch cross-service contract drift the per-service tests (each mocking its own
dependencies) can't. Building it uncovered — and this change set fixes — the following:

1. **Bridge ↔ redemption voucher-resume mismatch (fixed).** The bridge emitted the
   `loyalty.fulfillment.resume.v1` event with fields `jobHandle` / `status` / `voucherCode`, but
   `loyalty-redemption` consumes `externalRef` / `outcome` / `payload.voucherCode`. The async
   voucher saga could therefore never commit. `VoucherWebhookTranslator` + `FulfillmentResume` now
   emit the consumer's shape (`READY -> SUCCESS`, voucher code in `payload`).
2. **mobile-bff → core unintegrated (fixed).** `mobile-bff`'s `CoreClient` called core endpoints
   that don't exist (`POST .../opt-in`, `/balance`, `/tier`, ...). `optIn` / `getBalance` /
   `getTier` now call core's real API (`POST /members`, `GET /projection`).
3. **Rule activation can't go through admin-bff (worked around in the test).** Activating a rule to
   `ACTIVE` requires a `bepApprovalRef`, but admin-bff's `RuleStatusRequest` doesn't carry one. The
   test authors the DRAFT via admin-bff and activates via earning's own approval-gated API.
4. **Gradle wrapper jars were missing for 6 of 7 services (added).** Only `loyalty-integration-bridge`
   had its `gradle/wrapper/gradle-wrapper.jar`; the others had only the `.properties`, so their
   image builds would fail. Seeded from the bridge's jar (all pin Gradle 9.5.1; `.gitignore`
   already un-ignores the jar).
5. **Image build failed under Docker file-system watching (fixed).** Every service's build died at
   Gradle startup with `Cannot create service ... FileWatchingFilter ... GlobalCacheLocations` on
   this host (overlay FS / WSL2). Added `--no-watch-fs` to the `gradlew bootJar` invocation in
   `src/Dockerfile` — file watching is meaningless for a one-shot image build.
6. **Postgres aborted on a fresh volume (fixed).** `src/docker/postgres-init.sql` re-ran
   `CREATE DATABASE loyalty_core`, but `POSTGRES_DB=loyalty_core` (compose) already created it, so
   the init script errored (`database "loyalty_core" already exists`) and Postgres exited (3) —
   taking every db-backed service down with it. Removed that redundant line (the file's own comment
   already says POSTGRES_DB creates it).
7. **mobile-bff ↔ redemption reward-list shape mismatch (fixed).** redemption's
   `GET /programs/{id}/rewards/eligible` returns a bare JSON array, but mobile-bff deserialised it as
   a paged `RewardPage` object → 500. `RedemptionClient.listEligibleRewards` now reads the array and
   wraps it into `RewardPage`.
8. **core ↔ redemption commit-response mismatch (fixed).** core's `POST /reservations/{id}/commit`
   returned a `ReservationResponse`, but redemption deserialised it as a `LedgerEntryResponse`; under
   Jackson 3 a `null` mapped onto a primitive `long` (`FAIL_ON_NULL_FOR_PRIMITIVES`) → 500. Worse,
   core's commit didn't return the Redeemed ledger-entry id the saga records (`ledger_entry_id`,
   "non-null once COMMITTED"). Fixed both sides: core's commit now returns the entry id on
   `ReservationResponse.ledgerEntryId`; redemption reads it.

### Caveat — wrapper jars are gitignored

The `gradle-wrapper.jar`s seeded in finding #4 are present on disk (build passes) but the repo's
`.gitignore` actually excludes them (the `!gradle/wrapper/gradle-wrapper.jar` negation is defeated by
a broader rule — only the bridge's jar is tracked, force-added historically). To make the build fix
persist for a fresh clone / CI:

```bash
git add -f src/loyalty-*/gradle/wrapper/gradle-wrapper.jar
```

Still-open, not exercised here (per-service ITs cover the units): admin-bff's other unused
`CoreClient`-style gaps (e.g. mobile-bff `listEnrolledPrograms`/`transactions`/`expiring-points`
still target core endpoints that don't exist — out of this flow's scope).
