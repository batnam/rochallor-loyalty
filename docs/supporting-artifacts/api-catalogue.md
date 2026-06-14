# Rochallor Loyalty Platform — API Catalogue

> **Artifact §11.4** of [`enterprise-architect.md`](../enterprise-architect.md#114-supporting-artifacts-to-build).
> OpenAPI 3.0.3 contracts for the Rochallor Loyalty Platform. Two **edge-tier** specs for the BFFs (consumed via AWS API Gateway by external clients; these specs are the *contract* surface for the BFF L3 views — [`level-3-loyalty-mobile-bff.md`](../c4/level-3-loyalty-mobile-bff.md), [`level-3-loyalty-admin-bff.md`](../c4/level-3-loyalty-admin-bff.md) — which add the internal component breakdown) and four **internal-tier** specs under [`docs/openapi/internal/`](../../public/docs/openapi/internal/) for sibling-service REST surfaces (reached only inside the cluster over mTLS — every operation is marked `x-internal: true` and the spec sets `info.x-api-tier: internal`).

### Edge tier (consumed via API Gateway)

| Spec | Audience | Edge route | Aggregates |
|---|---|---|---|
| [`openapi/loyalty-mobile-bff.yaml`](../openapi/loyalty-mobile-bff.yaml) | Customer (Mobile Banking App) | `/api/loyalty/mobile/*` | core, redemption, campaign |
| [`openapi/loyalty-admin-bff.yaml`](../openapi/loyalty-admin-bff.yaml) | Bank employee (BEP) | `/api/loyalty/admin/*` | core, earning, redemption, campaign |

### Internal tier (mTLS only, in-cluster)

Documented for contract-testing, codegen, and cross-team handoff inside the Loyalty platform team. **No `Authorization` header** — caller identity is the calling service's Kubernetes service-account mTLS certificate. All four specs share the same `mutualTLS` security scheme; per-end-user AuthZ remains the BFF's responsibility on the inbound edge call.

| Spec | Service | In-cluster URL | Surface highlight | Ops |
|---|---|---|---|---|
| [`openapi/internal/loyalty-core.yaml`](../openapi/internal/loyalty-core.yaml) | `loyalty-core` | `https://loyalty-core.loyalty.svc.cluster.local` | Ledger API (`POST /ledger/earn`) + Reservation API (`POST /reservations`,`/commit`,`/release`) + Member projection read | 5 |
| [`openapi/internal/loyalty-earning.yaml`](../openapi/internal/loyalty-earning.yaml) | `loyalty-earning` | `https://loyalty-earning.loyalty.svc.cluster.local` | Earn Source registry + Rule CRUD / dry-run / activation | 6 |
| [`openapi/internal/loyalty-redemption.yaml`](../openapi/internal/loyalty-redemption.yaml) | `loyalty-redemption` | `https://loyalty-redemption.loyalty.svc.cluster.local` | Submit-and-poll redemption Saga + Reward / Reward Type catalogue | 8 |
| [`openapi/internal/loyalty-campaign.yaml`](../openapi/internal/loyalty-campaign.yaml) | `loyalty-campaign` | `https://loyalty-campaign.loyalty.svc.cluster.local` | Campaign + Drawing CRUD + T-13 `recordEntry` + winner records | 9 |

`loyalty-integration-bridge` has no inbound internal REST surface (its only synchronous inbound is the partner voucher webhook documented in [`docs/c4/level-3-loyalty-integration-bridge.md`](../c4/level-3-loyalty-integration-bridge.md)) — its contracts are AsyncAPI-only.

---

## 1. Authentication & authorization

Both BFFs validate a JWT issued by the **Authentication Service** (Arch §7.1); there is no Loyalty-specific login.

| | Mobile BFF | Admin BFF |
|---|---|---|
| Realm | `retail-banking` (customer) | `business-employee-portal` (employee) |
| Identity | `memberId` derived from JWT `sub`→`customerId`; **never passed in path** | acts on any member; `memberId` in path |
| AuthZ | implicit (the member can only see their own data) | role-gated: `loyalty-cs-maker`, `loyalty-cs-checker`, `loyalty-campaign-manager`, `loyalty-fraud-ops`, `loyalty-admin`, `loyalty-readonly` |
| Program scope | `{programId}` in path; member must be enrolled | `PROGRAM_ADMIN` per-Program scope — an action in Program A cannot touch Program B |
| Step-up | tx-PIN re-auth for high-value redemptions (`X-Step-Up-Token`) | n/a |
| Approvals | n/a | money-equivalent + economic-config changes are **delegated to BEP's Approval Workflow** — Loyalty raises/confirms; BEP owns 4-eyes, Job Roles, caps |

**Out of band:** creating/activating/sunsetting/closing a Program is a **DB migration**, not an Admin BFF operation. The Admin BFF only *configures* already-created Programs. Maker-checker is **not** built in Loyalty — it is delegated to BEP.

---

## 2. Conventions

| Concern | Decision |
|---|---|
| **OpenAPI version** | 3.0.3 — broadest tooling/codegen support. (3.1 would align with the AsyncAPI/JSON-Schema dialect but lags in some generators.) |
| **Program identity** | every Points-bearing response embeds `ProgramRef {programId, programCode}` — non-null from v1. v1 clients read but don't render it; v2 switches on it. |
| **Points / money** | `integer` `int64`; signed where a balance may go negative. |
| **Idempotency** | `POST /redemptions` requires an `Idempotency-Key` header. Admin writes are not idempotent (interactive, single-actor). |
| **Errors** | RFC 7807 `Problem` with a Loyalty-specific `code` (e.g. `STEP_UP_REQUIRED`, `TCS_NOT_ACCEPTED`, `BALANCE_NEGATIVE`, `CAP_EXCEEDED`, `CHECKER_EQUALS_MAKER`, `DSL_INVALID`). |
| **Pagination** | opaque `cursor` + `limit` (max 100) on list endpoints; response carries `nextCursor`. |
| **Transport** | HTTPS via API Gateway at the edge; BFF→service hops are REST/JSON over mTLS (Arch §7.1). |

---

## 3. Endpoint inventory

### 3.1 `loyalty-mobile-bff` (14 operations)

| Method | Path | Purpose |
|---|---|---|
| GET | `/me/programs` | Enrolled-programs array |
| POST | `/me/programs/{programId}/opt-in` | Explicit opt-in + T&Cs acceptance |
| POST | `/me/programs/{programId}/opt-out` | Opt out |
| POST | `/me/programs/{programId}/tcs-acceptance` | Accept T&Cs for an auto-enrolled Program (unlocks earning) |
| GET | `/me/programs/{programId}/balance` | Redeemable / effective / qualifying balances |
| GET | `/me/programs/{programId}/transactions` | Ledger history (paginated) |
| GET | `/me/programs/{programId}/tier` | Tier + progress to next |
| GET | `/me/programs/{programId}/expiring-points` | Cohorts expiring soon |
| GET | `/me/programs/{programId}/rewards` | Eligible reward catalogue |
| GET | `/rewards/{rewardId}` | Reward detail |
| POST | `/redemptions` | Redeem (200 sync / 202 async; step-up for high-value) |
| GET | `/redemptions/{redemptionId}` | Poll redemption status |
| GET | `/me/programs/{programId}/campaigns` | Live eligible campaigns |
| GET | `/me/programs/{programId}/drawing-entries` | My sweepstakes entries + winner status |

### 3.2 `loyalty-admin-bff` (21 operations)

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/members` | readonly+ | Find members by customerId |
| GET | `/members/{memberId}` | readonly+ | Member detail |
| GET | `/members/{memberId}/programs/{programId}/ledger` | readonly+ | Full ledger (audit) |
| POST | `/approval-requests` | cs-maker / campaign-mgr / admin | Raise a change (adjustment / economic config activation) for BEP approval |
| GET | `/approval-requests` | readonly+ | List Loyalty's raised requests (authoritative inbox is BEP's) |
| POST | `/approval-requests/{id}/confirm` | BEP workflow (mTLS) | Apply/reject on BEP's decision (hardened confirm seam) |
| GET | `/programs/{programId}/earn-sources` | readonly+ | Earn Source registry |
| GET·POST | `/programs/{programId}/rules` | admin/campaign-mgr | List / author rules (DSL) |
| POST | `/programs/{programId}/rules/{ruleId}/dry-run` | admin/campaign-mgr | Replay rule, no side effects |
| PATCH | `/rules/{ruleId}` | admin/campaign-mgr | Activate / archive |
| GET | `/reward-types` | readonly+ | Reward Type catalogue |
| GET·POST | `/programs/{programId}/rewards` | admin | List / create rewards |
| PATCH | `/rewards/{rewardId}` | admin | Activate / archive |
| GET·POST | `/programs/{programId}/campaigns` | campaign-mgr | List / create campaigns |
| PATCH | `/campaigns/{campaignId}` | campaign-mgr | Transition campaign |
| POST | `/campaigns/{campaignId}/drawings` | campaign-mgr | Add drawing |
| GET | `/drawings/{drawingId}/winners` | readonly+ | Winner records (audit-replayable) |
| GET | `/fraud/alerts` | fraud-ops | Velocity fraud alerts |

---

## 4. Reconciliation & design notes

- **A1 — Sweepstakes entry has no dedicated endpoint.** Per Arch §4.6.5 a sweepstakes entry is a `POST /redemptions` against a sweepstakes-entry reward; the mobile API exposes only read of entries (`/drawing-entries`). This keeps one redemption pipeline rather than a parallel entry API.
- **A2 — Step-up modeled as a header + 403.** §7.1 mandates tx-PIN re-auth for high-value redemptions but doesn't specify the wire shape. Modeled as an optional `X-Step-Up-Token` header; absence on a high-value redeem returns `403` with `code=STEP_UP_REQUIRED`. The challenge/verify round-trip itself is a BFF↔Authentication-Service concern (L2 T-07), not an edge endpoint.
- **A3 — `qualifyingBalance` is exposed but not rendered.** The qualifying number is never shown raw to customers. The API returns it (for the progress bar computation); rendering policy is the Mobile client's responsibility. The `tier` endpoint additionally returns `progressPercent`/`pointsToNextTier`.
- **A4 — Internal service REST is now in scope** under [`docs/openapi/internal/`](../../public/docs/openapi/internal/) (four specs — `loyalty-core`, `loyalty-earning`, `loyalty-redemption`, `loyalty-campaign`; 28 ops total). Every operation is marked `x-internal: true` and every spec sets `info.x-api-tier: internal`, so generators / gateways can mechanically distinguish edge from internal. Promoting these gives contract-test parity with the AsyncAPI tier (each Loyalty service had Kafka contracts but no REST contracts before this change). The partner `POST /webhook/voucher` is *not* a Loyalty-owned REST surface; it lives in the Bridge L3 doc.
- **A5 — Maker-checker delegated to BEP.** *Resolved.* Loyalty does not build maker-checker; adjustments and economic config activation are raised via `POST /approval-requests` and applied via `/confirm` once BEP's Approval Workflow approves. v1 default scope: economic items (adjustments, rule/reward/tier/T&Cs activation) are approval-gated; non-economic campaign-shell edits apply directly. The confirm seam is authenticated by mTLS + a BEP approval assertion (threat-model DD-9).

---

## 5. Validation

All specs parse with local `$ref`s resolving and lint clean under `redocly lint` (OpenAPI 3.0.3). **6 documents, 63 operations total:**

- Edge tier — 2 docs, 35 ops (14 mobile + 21 admin).
- Internal tier — 4 docs, 28 ops (5 core + 6 earning + 8 redemption + 9 campaign); every op carries `x-internal: true`, every spec sets `info.x-api-tier: internal`.
