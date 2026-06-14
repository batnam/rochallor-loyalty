# Running the platform locally with Docker

One Postgres (a database per service), one single-node KRaft Kafka, and all seven
loyalty services, wired together on a single Compose network.

## Start everything

```bash
cd src
docker compose up --build
```

First run builds seven images (Java 25 / Spring Boot 4 via each project's Gradle
wrapper) — the Gradle distribution and dependencies are cached after the first build.

## Services & ports

| Service                      | URL (host)              |
|------------------------------|-------------------------|
| loyalty-core                 | http://localhost:8081   |
| loyalty-earning              | http://localhost:8082   |
| loyalty-redemption           | http://localhost:8083   |
| loyalty-campaign             | http://localhost:8084   |
| loyalty-integration-bridge   | http://localhost:8085   |
| loyalty-mobile-bff           | http://localhost:8090   |
| loyalty-admin-bff            | http://localhost:8091   |
| Postgres                     | localhost:5432 (loyalty/loyalty) |
| Kafka (host clients)         | localhost:29092         |

Inside the network, services talk to each other by name (`loyalty-core:8081`, …)
and to infra at `postgres:5432` / `kafka:9092`.

## Common commands

```bash
docker compose up -d --build          # start detached
docker compose logs -f loyalty-core   # tail one service
docker compose build loyalty-earning  # rebuild one image
docker compose down                   # stop (keeps the pgdata volume)
docker compose down -v                # stop and wipe the database volume
```

## Notes

- The four JPA services (core, earning, redemption, campaign) create their own schema
  via Flyway on startup; the databases only need to exist (created by
  `docker/postgres-init.sql`).
- `loyalty-redemption` calls an external Payment Hub and a 3rd-party voucher provider
  that have no local stub, so those redemption flows fail locally. Everything else —
  membership, ledger, earning, campaigns/drawings, and BFF aggregation — runs fully.
- No service ships Spring Boot Actuator, so there are no `/actuator/health` endpoints;
  startup ordering is gated only on Postgres and Kafka being healthy.
