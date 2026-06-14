# loyalty-admin-bff

The **Bank Employee Portal (BEP) edge API** — turns BEP operator actions into role-gated reads/writes
across the loyalty backend ([C4 L2 BFFs](../../docs/c4/level-2-containers.md);
[`loyalty-admin-bff.yaml`](../../docs/openapi/loyalty-admin-bff.yaml)).

Behind AWS API Gateway (route `/api/loyalty/admin/*`) it **aggregates only** — it owns no datastore and
produces/consumes no Kafka. It fans out over REST to:

| Upstream | What the BFF uses it for |
|---|---|
| `loyalty-core` | member lookup/detail, ledger audit view, the approval-request store |
| `loyalty-earning` | earn-source registry, Earning Rule authoring + dry-run + activation |
| `loyalty-redemption` | Reward Type catalogue, per-Program Reward authoring |
| `loyalty-campaign` | campaign + drawing CRUD, lifecycle transitions, winner records |
| `loyalty-integration-bridge` | velocity-anomaly fraud alerts (Fraud-Ops) |

> **New here?** Read [`DETAILED-DESIGN.md`](DETAILED-DESIGN.md) — a self-contained design + user guide
> (identity + role gating, the approval lifecycle, authoring fan-out with `X-Actor` audit, fraud reads,
> error translation) with sequence diagrams.

## Invariants it upholds

- **Identity is injected by the gateway; the BFF does no token handling.** A gateway **Authentication
  Service** (Lambda) verifies the employee token and injects the employee identity as query parameters
  (Arch §7.1) — `userId` (the employee actor's user id) and `roles` (comma-separated Loyalty roles, e.g.
  `loyalty-cs-maker,loyalty-readonly`). `EmployeeIdentityResolver` reads those two params; the BFF never
  sees or decodes a JWT and never reads `realm_access.roles`. Query params (not body, not headers) are
  used so the actor gates **every** endpoint including GET reads. A missing/blank `userId` param is a
  `401`. The employee `userId` is distinct from `customerId` (the bank CIF, the customer *acted upon*).
- **Role-gated at the edge.** Each operation requires one of the Loyalty roles (`loyalty-cs-maker`,
  `loyalty-cs-checker`, `loyalty-campaign-manager`, `loyalty-fraud-ops`, `loyalty-readonly`);
  `loyalty-admin` is a wildcard. A missing role is a `403`. Per-Program scope (PROGRAM_ADMIN) is enforced
  upstream.
- **No maker-checker in Loyalty.** Money-equivalent and economic-config changes are *raised* via
  `POST /approval-requests` and *applied* via `POST /approval-requests/{id}/confirm` once the bank's **BEP
  Approval Workflow** decides. BEP owns routing, Job Roles, 4-eyes, and caps; Loyalty stores only a
  `bepApprovalRef`. Activation (→ ACTIVE) and point-cost/economic changes are approval-gated **upstream** —
  the owning service rejects them without an approval ref; the BFF just forwards.
- **Thin aggregation, Anti-Corruption clients.** Each controller method delegates to a per-upstream
  `RestClient`; the operator user id is forwarded as `X-Actor` so the owning service's hash-chained audit
  records who acted. Pinned to HTTP/1.1.
- **Upstream errors are translated, not leaked.** `UpstreamErrorHandler` maps an upstream 4xx/5xx into the
  same status + RFC-7807 `code` (e.g. `MISSING_APPROVAL`, `DSL_INVALID`, `CAP_EXCEEDED`) at the edge.

## Slice status

| Slice | Status |
|---|---|
| Employee identity + role gating (gateway-injected `userId` + `roles` query params), argument resolver | ✅ scaffolded |
| Members (lookup, detail, ledger audit) → core | ✅ scaffolded |
| Approvals (raise / list / confirm, forwarded to core's store) → core | ✅ scaffolded |
| Earning Rules (earn sources, rule CRUD, dry-run, activate/archive) → earning | ✅ scaffolded |
| Rewards (reward types, reward authoring + status) → redemption | ✅ scaffolded |
| Campaigns (campaign/drawing CRUD, transitions, winners) → campaign | ✅ scaffolded |
| Fraud alerts (Fraud-Ops) → integration-bridge | ✅ scaffolded |
| Upstream-error translation → RFC-7807 Problem at the edge | ✅ scaffolded |

**Deferred from this scaffold (matches platform deferrals):** employee-token **verification** (the gateway
Authentication Service's job — the BFF trusts the injected `userId` + `roles`); the **mTLS + BEP approval
assertion** on the hardened `confirm` seam (the `bepApprovalRef` is forwarded, not cryptographically
verified here); per-Program **scope** enforcement (upstream); mTLS wiring (cluster infra).

> **Upstream dependency notes.** Two design seams the BFF defines and stubs in the IT:
> - **Approval store + apply-on-confirm lives in `loyalty-core`** (the Shared Kernel) so the BFF stays
>   aggregation-only. On `confirm/APPROVED`, core applies the change atomically — writing the `Adjusted`
>   ledger entry itself, or invoking the owning service's hardened `confirm` seam with the `bepApprovalRef`.
> - **Fraud alerts are read from `loyalty-integration-bridge`**, which owns the Velocity-Anomaly consumer
>   of `loyalty.fraud.alert.v1` (there is no separate `loyalty-fraud` container). The BFF aggregates that
>   read rather than consuming Kafka itself — this keeps the BFF aggregation-only, a deliberate refinement
>   of the L2 async diagram's direct MSK→admin-bff arrow.

## Build & run

Stack: **Java 25** · **Spring Boot 4.x** · **Spring MVC (RestClient)** · **Gradle (Kotlin DSL)** —
synced with the backend services. No JPA / Flyway / Kafka (aggregation only).

```bash
./gradlew test     # 7 WireMock-backed integration tests (all five upstreams stubbed) — no Docker needed
./gradlew bootRun  # needs core / earning / redemption / campaign / bridge base-URLs (see application.yml)
```

> Requires a **JDK 25** toolchain. The IT stubs all five upstreams with a single WireMock server, so no
> sibling service is needed to run it.
