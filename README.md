# Rochallor Loyalty Platform

> A flexible, config-driven loyalty engine for retail digital banks — built as a satellite domain to a Host Bank Platform.

[![Version](https://img.shields.io/badge/version-0.1.0--pre-blue)](https://github.com/batnam/rochallor-loyalty/releases/tag/v0.1.0-pre)
[![License](https://img.shields.io/badge/license-Apache%202.0-green)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange)](https://openjdk.org/releases/25/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.x-brightgreen)](https://spring.io/projects/spring-boot)
[![Build](https://img.shields.io/badge/build-Gradle%209.5-blue)](https://gradle.org/)
[![pre-release](https://img.shields.io/badge/status-pre--release-yellow)](https://github.com/batnam/rochallor-loyalty/releases)

---

## Overview

The **Rochallor Loyalty Platform** converts customer banking activity into loyalty points, manages member tier progression, administers a configurable reward catalog, and orchestrates two-phase redemptions — all with strict audit trails, fraud controls, and Maker-Checker governance.

It is designed as a **satellite domain**: it never sits on a critical payment path, never owns customer PII, and delegates authentication, notifications, and core-banking operations to the Host Bank Platform (HBP).

**Built once, deployed per bank.** Deployment-specific identity (URLs, topic names, credential references) is isolated in `docs/deployments/<bank>/`. 

Online Doc: https://batnam.github.io/rochallor-loyalty/

---

## Key Features

| Feature | Description |
|---|---|
| **Config-driven Earning Rules** | DSL-based rule engine; admins author and activate rules without engineering cycles |
| **Append-only Point Ledger** | Immutable ledger entries; FIFO expiry; corrections are compensating entries |
| **Two-phase Redemption** | `reserve → commit / release` saga; transient reservation state isolated from permanent ledger |
| **4 live Reward Types** | Cashback, Bill-Payment Voucher, Third-Party Voucher, Sweepstakes Entry |
| **Tier Framework** | Configurable tier ladder with rolling/lifetime/calendar qualifying windows |
| **Fraud Controls** | Velocity anomaly detection, per-source/per-member/per-rule caps, refund clawback |
| **Campaigns & Drawings** | Sweepstakes campaigns with admin-scheduled winner selection |
| **Maker-Checker Governance** | All economic actions delegated to the Host Bank's BEP Approval Workflow |

---

## Architecture

7 microservices across 8 bounded contexts, each with physical data isolation (dedicated Postgres instance). Integration via explicit, versioned Kafka domain events — not CDC.

```
┌──────────────────────────────────────────────────────────────┐
│               Host Bank Platform (external)                  │
│        Auth · Payment Hub · Customer Service · Notif         │
└──────────┬───────────────────────────────┬───────────────────┘
           │ Kafka ingress events           │ REST callbacks
┌──────────▼───────────────────────────────▼───────────────────┐
│              loyalty-integration-bridge (8085)               │
│         Translation · Velocity Fraud Detection               │
└────────────────────────┬─────────────────────────────────────┘
                         │ Kafka canonical events
          ┌──────────────┼─────────────────┐
          ▼              ▼                 ▼
  loyalty-earning  loyalty-core    loyalty-redemption
     (8082)           (8081)           (8083)
  Rule Engine    Membership +    Reward Catalog +
  DSL + Caps     Point Ledger    Fulfillment Saga
                 Tier Projection
                         │
                  loyalty-campaign
                     (8084)
                  Campaigns + Drawings
          │                          │
┌─────────▼──────────────────────────▼─────────┐
│              Edge Tier (API Gateway)          │
│  loyalty-mobile-bff (8090)                    │
│  loyalty-admin-bff  (8091)                    │
└───────────────────────────────────────────────┘
```

See the full [C4 model](docs/c4/) for system context, container topology, and per-service component views.

---

## Services

| Service | Port | Bounded Context(s) | Responsibility |
|---|---|---|---|
| `loyalty-core` | 8081 | Membership + Ledger | Member lifecycle, Point Ledger (append-only), Tier projection, Reservation manager |
| `loyalty-earning` | 8082 | Earning | Earn Source registry, Rule Engine (DSL evaluator), cap enforcement |
| `loyalty-redemption` | 8083 | Reward + Fulfillment | Reward catalog, two-phase redemption saga, fulfillment adapters |
| `loyalty-campaign` | 8084 | Campaign + Drawing | Campaign CRUD, Drawing scheduler, sweepstakes entry registry |
| `loyalty-integration-bridge` | 8085 | Integration | Ingress gateway, bank-event translation, velocity anomaly detection |
| `loyalty-mobile-bff` | 8090 | _(aggregator)_ | Customer-facing read/write aggregation (stateless) |
| `loyalty-admin-bff` | 8091 | _(aggregator)_ | Employee-facing config, audit, and fraud-ops (stateless) |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.x, Spring MVC |
| Database | PostgreSQL 16 (per-service RDS instances) |
| Messaging | Apache Kafka (KRaft in dev, AWS MSK in prod) |
| Build | Gradle 9.5.1 (Kotlin DSL) + Flyway migrations |
| Distributed Lock | ShedLock |
| Testing | JUnit 5, Testcontainers, WireMock (unit/integration) |
| E2E Testing | pytest, kafka-python, requests |
| Deployment | Docker Compose (dev) · Kubernetes EKS + ArgoCD (prod) |

---

## Quick Start

### Prerequisites

- **Docker 24+** and Docker Compose v2
- **Java 25** (for local service builds)
- **Python 3.10+** (for E2E tests only)

### 1. Run the full stack locally

```bash
cd src
docker compose up --build
```

This starts all 7 services, Postgres instances, and Kafka (KRaft mode). Health-check endpoints:

```
GET http://localhost:8081/actuator/health   # loyalty-core
GET http://localhost:8082/actuator/health   # loyalty-earning
GET http://localhost:8083/actuator/health   # loyalty-redemption
GET http://localhost:8084/actuator/health   # loyalty-campaign
GET http://localhost:8085/actuator/health   # loyalty-integration-bridge
GET http://localhost:8090/actuator/health   # loyalty-mobile-bff
GET http://localhost:8091/actuator/health   # loyalty-admin-bff
```

### 2. Run E2E tests

```bash
cd e2e
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pytest -v
```

The E2E suite drives a complete customer journey:

1. Member opt-in
2. Card spend event ingested via Kafka
3. Rule evaluation → points awarded
4. Cashback redemption (two-phase saga)
5. Sweepstakes entry

### 3. Build a single service

```bash
cd src/loyalty-core
./gradlew build
```

---

## Documentation

| Document | Description |
|---|---|
| [Domain Context](CONTEXT.md) | Glossary, bounded contexts, aggregates, invariants, event flows |
| [Enterprise Architecture](docs/enterprise-architect.md) | 4-layer EA (business, application, data, technology) |
| [C4 Model — System Context](docs/c4/level-1-system-context.md) | External systems, personas, enterprise boundary |
| [C4 Model — Containers](docs/c4/level-2-containers.md) | 7-service topology, REST and Kafka edges |
| [C4 Model — Components](docs/c4/) | Per-service component views (L3, one file per service) |
| [OpenAPI — Mobile BFF](docs/openapi/loyalty-mobile-bff.yaml) | 14 customer-facing operations |
| [OpenAPI — Admin BFF](docs/openapi/loyalty-admin-bff.yaml) | 21 employee-facing operations |
| [OpenAPI — Internal](docs/openapi/internal/) | 4 internal specs (mTLS-only, 28 operations total) |
| [AsyncAPI — Ingress](docs/asyncapi/loyalty-ingress.yaml) | Bank-side Kafka event contracts |
| [AsyncAPI — Internal](docs/asyncapi/) | Per-service internal topic contracts |
| [Earning Rule DSL](docs/dsl/) | JSON Schema + 8 worked examples |
| [API Catalogue](docs/supporting-artifacts/api-catalogue.md) | Cross-service API reference |
| [Event Catalogue](docs/supporting-artifacts/event-catalogue.md) | All Kafka topics and payload schemas |
| [Data Catalogue](docs/supporting-artifacts/data-catalogue.md) | Table schemas, relationships, retention |
| [Threat Model](docs/supporting-artifacts/threat-model.md) | STRIDE analysis |
| [Test Strategy](docs/supporting-artifacts/test-strategy.md) | Unit / integration / E2E coverage approach |
| [User Guide](docs/USER-GUIDE.md) | Operational runbook |

Full docs site: **[batnam.github.io/rochallor-loyalty](https://batnam.github.io/rochallor-loyalty)**

---

## Design Principles

Eight load-bearing decisions that shape the platform:

1. **Per-service Postgres** — enforces bounded-context isolation; no cross-service joins at the data layer.
2. **Domain events, not CDC** — producers own event schemas; portable across bank deployments.
3. **Append-only Ledger** — corrections are compensating entries; the ledger is the source of truth for all point movements.
4. **Two-phase redemption** — separates transient `point_reservation` state from permanent ledger entries.
5. **Anti-Corruption Layers** — all external systems (Payment Hub, Customer Service, partners) accessed only through internally-owned adapters.
6. **Delegated Maker-Checker** — economic actions use the Host Bank's BEP Approval Workflow; Loyalty stores references, not workflow state.
7. **Tier-2 availability (99.95%)** — deliberately below the Host Bank's 99.99%; the mobile app must degrade gracefully.
8. **Config-driven extensibility** — Earn Sources, Rules, Rewards, Tiers, and Campaigns are admin-managed catalog entries, not hardcoded values.

---

## Project Status (v0.1.0-pre)

This is a **pre-release**. The domain model, event contracts, and API specifications are stable. The implementation is in active development.

| Area | Status |
|---|---|
| Domain model & CONTEXT.md | Stable |
| C4 architecture diagrams (L1–L3) | Stable |
| OpenAPI + AsyncAPI contracts | Stable |
| Earning Rule DSL schema | Stable |
| Service implementations | In progress |
| E2E test suite | Working (happy path) |
| Kubernetes manifests / Helm charts | Not yet included |
| CI/CD pipelines | Not yet included |

### v0.1.0 Scope

- Single seeded Program with full member lifecycle (opt-in / opt-out)
- Earning Rule Engine with DSL evaluation, conflict summing, caps
- Point Ledger with FIFO expiry and Tier projection
- 4 Reward Types: Cashback, Bill-Payment Voucher, Third-Party Voucher, Sweepstakes Entry
- Two-phase redemption + fulfillment adapter framework
- Campaign & Drawing (sweepstakes)
- Fraud controls: velocity anomaly detection, per-source caps, refund clawback

### Deferred to v1.x

- `PAY_WITH_POINTS` (real-time POS saga with Payment Hub)
- `FEE_WAIVER`, `MATERIAL_GOODS`, `CHARITY_DONATION`, `TIER_BOOST` Reward Types
- Multi-program activation
- Advanced fraud ML scoring
- Kubernetes manifests and Helm charts
- Multi-region active-active deployment

---

## Contributing

1. **Fork** the repository
2. **Read** [CONTEXT.md](CONTEXT.md) — the domain glossary is the shared language across all decisions
3. **Check** [open issues](https://github.com/batnam/rochallor-loyalty/issues) for items labeled `ready-for-agent` or `ready-for-human`
4. **Open a PR** against `main` with a clear description of what changed and why

Please keep PRs focused. Each PR should trace to a single issue or a clearly stated goal.

### Issue Labels

| Label | Meaning |
|---|---|
| `needs-triage` | Newly opened; not yet assessed |
| `needs-info` | Blocked on missing information |
| `ready-for-agent` | Well-specified; suitable for AI-assisted implementation |
| `ready-for-human` | Requires human judgment or domain expertise |
| `wontfix` | Out of scope or intentionally declined |

---

## License

[Apache License 2.0](LICENSE)

---

> Built for regulated financial domains. Not affiliated with any specific bank or financial institution.
