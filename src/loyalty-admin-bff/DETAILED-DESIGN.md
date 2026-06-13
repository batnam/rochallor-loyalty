# loyalty-admin-bff — Detailed Design & User Guide

A self-contained companion to [C4 L2 BFFs](../../docs/c4/level-2-containers.md) and the BEP edge
contract ([`loyalty-admin-bff.yaml`](../../docs/openapi/loyalty-admin-bff.yaml)).

---

## 0. What this service is

The **Bank Employee Portal (BEP) edge API**. Behind AWS API Gateway (route `/api/loyalty/admin/*`) it is
an **aggregation-only** service — it owns **no datastore** and produces/consumes **no Kafka**. Each
request is a role-gated fan-out to one of five backend services:

| Upstream | Surface |
|---|---|
| `loyalty-core` | member lookup/detail, ledger audit view, the **approval-request store** |
| `loyalty-earning` | earn-source registry, Earning Rule authoring + dry-run + activation |
| `loyalty-redemption` | Reward Type catalogue, per-Program Reward authoring |
| `loyalty-campaign` | campaign + drawing CRUD, lifecycle transitions, winner records |
| `loyalty-integration-bridge` | velocity-anomaly fraud alerts (Fraud-Ops) |

Its job is **identity + role gating + aggregation + translation**, plus forwarding the operator identity
as `X-Actor` so each owning service's hash-chained audit records *who* acted.

---

## 1. Bounded context & neighbours

- **Inbound:** the Bank Employee Portal, via AWS API Gateway. A gateway **Authentication Service**
  (Lambda) verifies the employee token and injects the employee identity (`userId` + `roles`) as query
  parameters; the gateway also strips the `/api/loyalty/admin` prefix. The BFF does no token handling.
- **Outbound:** the five services above — REST only, each behind an Anti-Corruption `RestClient` pinned
  to HTTP/1.1. mTLS is provided by cluster infra.
- **Owns:** nothing durable. No tables, no topics.

---

## 2. Identity & role gating

**The BFF does no token handling at all.** A gateway **Authentication Service** (a Lambda) verifies the
employee token and **injects the employee identity into the request as query parameters** before it
reaches the BFF — `userId` (the employee user id) and `roles` (a comma-separated list of Loyalty roles,
e.g. `loyalty-cs-maker,loyalty-readonly`). The BFF never sees or decodes a JWT and never reads
`realm_access.roles`.

`EmployeeIdentityResolver` simply **reads those two query parameters** and builds an
`EmployeeIdentity{userId, Set<roles>}`, injected into every controller method. Query parameters (not the
body, not headers) are used because the actor must gate `requireAnyRole(...)` on **every** endpoint
including GET reads (which carry no body), and a `HandlerMethodArgumentResolver` cannot read the JSON body
without breaking `@RequestBody` — so the same uniform mechanism covers GET and POST/PATCH. A missing or
blank `userId` request parameter is a `401` (defence in depth — the request should never reach the BFF
without it).

The employee `userId` is **distinct from `customerId`**: `customerId` (the bank CIF) is the *customer
being acted upon* by member-scoped admin ops (e.g. `GET /members?customerId=...`), while `userId` is the
*employee actor*. The employee `userId` is forwarded downstream as the `X-Actor` header for the
hash-chained audit trail.

Each operation calls `requireAnyRole(...)`; a missing role is a `403`. `loyalty-admin` is a **wildcard**
over the functional roles (`loyalty-cs-maker`, `loyalty-cs-checker`, `loyalty-campaign-manager`,
`loyalty-fraud-ops`, `loyalty-readonly`). Per-Program scope (PROGRAM_ADMIN) is enforced upstream; this
BFF gates by role. The OpenAPI `employeeJwt` bearer security scheme is kept as **edge-level
documentation** (an employee token is still required at the gateway / Authentication Service), but the BFF
itself does not process the token.

<p align="center">
  <img src="images/admin-bff-dd-seq-rolegate.svg" alt="Role gating and member/ledger read sequence">
</p>

```plantuml
@startuml admin-bff-dd-seq-rolegate
title loyalty-admin-bff — Role gating & member/ledger read

actor       bep    as "Bank Employee Portal"
participant gw     as "AWS API Gateway\n+ Authentication Service (Lambda)"
participant res    as "EmployeeIdentityResolver"
participant ctl    as "MemberController"
participant client as "MemberClient"
participant core   as "loyalty-core"

bep -> gw : GET /api/loyalty/admin/members/{id}/programs/{pid}/ledger\n(employee token at the edge)
activate gw
gw -> gw : Authentication Service verifies token;\n strip /api/loyalty/admin
gw -> res : GET /members/{id}/programs/{pid}/ledger?userId&roles\n(identity injected as query params)
deactivate gw
activate res
res -> res : read userId + roles request params\n (no token, no decode)
alt missing / blank userId param
  res --> bep : 401 Problem {code: UNAUTHORIZED}
end
res -> ctl : EmployeeIdentity(userId, roles)
deactivate res
activate ctl
ctl -> ctl : requireAnyRole(cs-maker, cs-checker, readonly)
alt none held (and not loyalty-admin)
  ctl --> bep : 403 Problem {code: FORBIDDEN}
end
ctl -> client : getMemberLedger(memberId, programId, cursor, limit)
client -> core : GET /members/{id}/programs/{pid}/ledger?cursor&limit
core --> client : LedgerPage {items, nextCursor}
client --> ctl : LedgerPage
ctl --> bep : 200 OK LedgerPage
deactivate ctl
@enduml
```

---

## 3. Approvals — Loyalty raises, BEP decides, the owning service applies

Loyalty does **not** implement maker-checker. Money-equivalent and economic-config changes are **raised**
via `POST /approval-requests` (persisted `PENDING`) and **applied** via
`POST /approval-requests/{id}/confirm` once the bank's **BEP Approval Workflow** decides. BEP owns
routing, Job Roles, 4-eyes, and caps; Loyalty stores only a `bepApprovalRef`.

Because the BFF is aggregation-only, the **approval-request store + apply-on-confirm orchestration lives
in `loyalty-core`** (the Shared Kernel). The BFF is a thin forwarder. On `confirm/APPROVED`, core applies
the change atomically — writing the `Adjusted` ledger entry itself, or invoking the owning service's
**hardened confirm seam** with the `bepApprovalRef`. Direct economic writes that bypass the approval flow
(e.g. `PATCH /rewards` → ACTIVE without a ref) are **rejected upstream** and the error is translated at
the edge.

<p align="center">
  <img src="images/admin-bff-dd-seq-approval.svg" alt="Approval lifecycle and upstream activation-gate translation sequence">
</p>

```plantuml
@startuml admin-bff-dd-seq-approval
title loyalty-admin-bff — Approval lifecycle & upstream activation gate

actor       maker   as "BEP (maker)"
participant ctl     as "ApprovalController"
participant client  as "ApprovalClient"
participant core    as "loyalty-core (approval store)"
participant owner   as "owning service\n(redemption / earning)"
participant workflow as "BEP Approval Workflow"

== Raise (PENDING) ==
maker -> ctl : POST /approval-requests?userId&roles {type, payload}\n(identity injected as query params by the gateway Auth Service)
activate ctl
ctl -> ctl : requireAnyRole(cs-maker, campaign-manager)
ctl -> client : create(actor=userId, req)
client -> core : POST /approval-requests  (X-Actor=userId)
core --> client : 201 ApprovalRequest {requestId, status=PENDING}
client --> ctl : ApprovalRequest
ctl --> maker : 201 Created {status=PENDING}
deactivate ctl

== BEP decides (out of band — Loyalty owns no 4-eyes) ==
workflow -> workflow : route by Job Role; enforce 4-eyes + caps; approve

== Confirm (apply / reject) ==
workflow -> ctl : POST /approval-requests/{id}/confirm\n{decision=APPROVED, bepApprovalRef}\n(mTLS service identity + BEP assertion)
activate ctl
ctl -> client : confirm(requestId, req)
client -> core : POST /approval-requests/{id}/confirm
activate core
alt decision = APPROVED
  alt type = ADJUSTMENT
    core -> core : write Adjusted ledger entry (atomic)
  else RULE_ACTIVATION / REWARD_CHANGE / TIER_CHANGE / TCS_VERSION
    core -> owner : apply via hardened confirm seam (bepApprovalRef)
    owner --> core : applied
  end
  core --> client : 200 {status=APPLIED, appliedRef, bepApprovalRef}
else REJECTED / not PENDING
  core --> client : 200 {status=REJECTED}  |  409 {code} if already applied
end
deactivate core
client --> ctl : ApprovalRequest
ctl --> workflow : 200 OK
deactivate ctl

== Direct economic write WITHOUT approval is rejected upstream ==
maker -> ctl : PATCH /rewards/{id}?userId&roles {status=ACTIVE}
ctl -> owner : PATCH /rewards/{id} {status=ACTIVE}  (X-Actor)
owner --> ctl : 409 Problem {code: MISSING_APPROVAL}
ctl --> maker : 409 {code: MISSING_APPROVAL}   (translated at the edge)
@enduml
```

---

## 4. Authoring & fraud — fan-out with `X-Actor` audit

The authoring surfaces (Earning Rules, Rewards, Campaigns) follow one shape: gate on
`loyalty-campaign-manager`, forward the body to the owning service with the operator id as `X-Actor`
(for the owning service's hash-chained audit), and return its response. **Activation** (→ ACTIVE) is
approval-gated upstream (§3); **drafting**, **dry-run**, **archive**, and **campaign transitions** apply
directly. Fraud alerts are read from the integration-bridge (which owns the velocity-anomaly consumer),
gated on `loyalty-fraud-ops`.

<p align="center">
  <img src="images/admin-bff-dd-seq-authoring.svg" alt="Authoring fan-out, dry-run and fraud read sequence">
</p>

```plantuml
@startuml admin-bff-dd-seq-authoring
title loyalty-admin-bff — Authoring, dry-run & fraud read

actor       mgr    as "BEP (campaign-manager)"
actor       fops   as "BEP (fraud-ops)"
participant rctl   as "EarningRuleController"
participant fctl   as "FraudController"
participant ecl    as "EarningClient"
participant fcl    as "FraudClient"
participant earn   as "loyalty-earning"
participant bridge as "loyalty-integration-bridge"

== Author a rule (DRAFT) ==
mgr -> rctl : POST /programs/{id}/rules?userId&roles {earnSourceId, dslJson}\n(identity injected as query params by the gateway Auth Service)
activate rctl
rctl -> rctl : requireAnyRole(campaign-manager)
rctl -> ecl : createRule(actor=userId, programId, req)
ecl -> earn : POST /programs/{id}/rules  (X-Actor=userId)
earn -> earn : validate DSL; INSERT rule (DRAFT); audit
earn --> ecl : 201 EarningRule {status=DRAFT}
ecl --> rctl : EarningRule
rctl --> mgr : 201 Created {status=DRAFT}
deactivate rctl

== Dry-run (no side effects) ==
mgr -> rctl : POST /programs/{id}/rules/{ruleId}/dry-run {eventReplayWindow}
rctl -> ecl : dryRun(programId, ruleId, req)
ecl -> earn : POST /programs/{id}/rules/{ruleId}/dry-run
earn --> ecl : {matchedEvents, totalQualifying, totalRedeemable}
ecl --> rctl : DryRunResult
rctl --> mgr : 200 OK DryRunResult
note right of earn : same interpreter as production;\n safe — the DSL has no side effects

== Fraud alerts (Fraud-Ops) ==
fops -> fctl : GET /fraud/alerts?programId&userId&roles\n(identity injected as query params by the gateway Auth Service)
activate fctl
fctl -> fctl : requireAnyRole(fraud-ops)
fctl -> fcl : listAlerts(programId, cursor, limit)
fcl -> bridge : GET /fraud/alerts?programId&cursor&limit
bridge --> fcl : {items:[FraudAlert], nextCursor}
fcl --> fctl : FraudAlertPage
fctl --> fops : 200 OK FraudAlertPage
deactivate fctl
@enduml
```

---

## 5. Error translation

Every outbound `RestClient` registers
[`UpstreamErrorHandler`](src/main/java/com/loyalty/adminbff/client/UpstreamErrorHandler.java) via
`defaultStatusHandler(HttpStatusCode::isError, …)`. An upstream `4xx/5xx` is lifted to a `BffException`
carrying the **same status** + RFC-7807 `code` (e.g. `MISSING_APPROVAL`, `DSL_INVALID`, `CAP_EXCEEDED`),
so the BEP sees the real cause instead of a `500`. A missing `userId` request parameter (`401`) and a
failed `requireAnyRole(...)` (`403`) are raised directly. `ProblemAdvice` renders the final Problem.

---

## 6. Implementation notes

- **Jackson 2/3 split:** open-JSON DTO fields (`payload`, `dslJson`, `fulfillmentParams`,
  `parameterSchema`, `eligibility`, `targetSegment`, `prize`) are typed `Object` (Map/List trees), not
  Jackson tree nodes — Spring Boot 4's web layer is Jackson 3 while the platform pins Jackson 2. (See
  [[spring-boot4-jackson2-3-split]].)
- **RestClients pinned to HTTP/1.1** — avoids flaky HTTP/2 negotiation against the WireMock stubs.
- **No datastore, no Kafka** — the build omits JPA / Flyway / Kafka / ShedLock / Postgres; the only test
  dependency beyond `spring-boot-starter-test` is WireMock.

---

## 7. Upstream dependency notes

Two design seams the BFF defines and stubs in the IT:

- **Approval store + apply-on-confirm lives in `loyalty-core`** (§3) so the BFF stays aggregation-only.
- **Fraud alerts are read from `loyalty-integration-bridge`**, which owns the Velocity-Anomaly consumer
  of `loyalty.fraud.alert.v1` (there is no separate `loyalty-fraud` container). The BFF aggregates that
  read rather than consuming Kafka itself — a deliberate refinement of the L2 async diagram's direct
  `MSK → admin-bff` arrow.

---

## 8. Run & operate

```bash
./gradlew test     # 17 tests: 7 WireMock IT (all five upstreams stubbed) + 10 unit (identity, role gating)
./gradlew bootRun  # needs core / earning / redemption / campaign / bridge base-URLs (see application.yml)
```

Requires a **JDK 25** toolchain. The IT stubs all five upstreams with one WireMock server, so the suite
runs with no sibling service and **no Docker**.
