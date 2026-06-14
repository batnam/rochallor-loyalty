# Rochallor Loyalty Platform — C4 Level 3 — Component — `loyalty-admin-bff`

| Field | Value |
|---|---|
| Version | 0.1 — Initial Draft |
| Status | DRAFT |
| Last updated | 2026-05-31 |
| Author | Nam Vu |
| Companion doc | [`docs/Digital-Loyalty-Arch.md`](../enterprise-architect.md) §10.3 |
| Preceding view | [`level-2-containers.md`](level-2-containers.md) |
| Sibling views | [`level-3-loyalty-mobile-bff.md`](level-3-loyalty-mobile-bff.md), [`level-3-loyalty-integration-bridge.md`](level-3-loyalty-integration-bridge.md) |
| Service guide | [`src/loyalty-admin-bff/DETAILED-DESIGN.md`](../../src/loyalty-admin-bff/DETAILED-DESIGN.md) |
| Glossary | [`CONTEXT.md`](../../CONTEXT.md) |

---

## 1. Purpose & Scope

This document is the **C4 Level 3 — Component** view for the `loyalty-admin-bff` service. Its single job is to answer:

> **What components live inside `loyalty-admin-bff`, how are employee identity and Loyalty roles derived and enforced, and how does the BFF aggregate five backend services into the Bank Employee Portal (BEP) contract?**

It zooms inside the single `loyalty-admin-bff` rectangle drawn at [L2 §3.1](level-2-containers.md#31-static-topology). The service is an **aggregation-only** edge: it owns **no datastore** and produces/consumes **no Kafka**. Beyond the BFF concerns shared with the mobile edge, it adds two things: **per-operation role gating** and **`X-Actor` propagation** so each owning service's hash-chained audit records who acted.

> **Note on the L3 set.** Earlier drafts deliberately omitted an L3 for the BFFs. Now that the admin BFF is implemented with a role-gating identity seam, per-tag controllers, six per-upstream ACL clients, and an upstream-error translator, an L3 component view earns its place.

**In scope:**

- The application-level components inside `loyalty-admin-bff`.
- The gateway-injected `userId` + `roles` query-param identity seam and `requireAnyRole(...)` gating.
- The six Anti-Corruption clients and the five upstreams they front.
- The approval lifecycle (raise → BEP decides → apply-on-confirm in core) and the upstream activation gate.

**Out of scope (deliberately):**

- The internals of the five upstream services — those are their own L3 views.
- The bank's **BEP Approval Workflow** (Job Roles, 4-eyes, caps, routing) — Loyalty stores only a `bepApprovalRef`.
- Employee-token verification and TLS termination — AWS API Gateway + its Authentication Service (L2).

---

## 2. Reading the Diagrams

`loyalty-admin-bff` has exactly one execution mode: **request-driven**. We use **three sub-views**:

| Sub-view | Scope | What it answers |
|---|---|---|
| **§3.1 Static Topology** | All components + six upstream clients + external neighbours | *What lives inside `loyalty-admin-bff` and how is the fan-out wired?* |
| **§3.2 Role-Gated Request** | Gateway → identity + role gate → controller → upstream (with `X-Actor`) | *How is a BEP action authorised and attributed?* |
| **§3.3 Approval Lifecycle** | Raise → BEP decides → confirm applies in core / owning service | *How does Loyalty defer maker-checker to BEP and still apply changes?* |

**Common legend** is identical to [`level-3-loyalty-redemption.md` §2](level-3-loyalty-redemption.md#2-reading-the-diagrams). Conventions specific to this service:

- A **green box** marks an **Anti-Corruption client** — the BFF's only point of contact with one upstream service.
- There is **no data tier**. The approval-request store lives in `loyalty-core`; the BFF forwards.

---

## 3. The Diagrams

### 3.1 Static Topology

<p align="center">
  <img src="../images/level-3-loyalty-admin-bff.svg" alt="">
</p>

```plantuml
@startuml level-3-loyalty-admin-bff
title C4 Level 3 — loyalty-admin-bff — Static Topology

skinparam shadowing false
skinparam defaultTextAlignment center
skinparam nodesep 24
skinparam ranksep 45

skinparam rectangle {
    roundCorner<<svc>>           20
    BorderStyle<<svc>>           dashed
    BackgroundColor<<svc>>       #f0f7ff
    BorderColor<<svc>>           #0b4884

    BackgroundColor<<component>> #0b4884
    FontColor<<component>>       #ffffff
    BorderColor<<component>>     #073661

    BackgroundColor<<acl>>       #00897b
    FontColor<<acl>>             #ffffff
    BorderColor<<acl>>           #00695c

    BackgroundColor<<ext>>       #b0bec5
    FontColor<<ext>>             #000000
    BorderColor<<ext>>           #78909c
}

skinparam package {
    BackgroundColor #fafafa
    BorderColor     #9e9e9e
    FontStyle       italic
}

' External neighbours
rectangle "Bank Employee Portal" <<ext>> as bep
rectangle "AWS API Gateway\n+ Authentication Service (Lambda)" <<ext>> as gw
rectangle "BEP Approval Workflow" <<ext>> as wf
rectangle "loyalty-core"         <<ext>> as core
rectangle "loyalty-earning"      <<ext>> as earn
rectangle "loyalty-redemption"   <<ext>> as redm
rectangle "loyalty-campaign"     <<ext>> as camp
rectangle "loyalty-integration-bridge" <<ext>> as bridge

rectangle "**loyalty-admin-bff**" <<svc>> {

  package "Edge / Identity" {
    rectangle "Employee Identity Resolver\n(reads userId + roles params)" <<component>> as res
    rectangle "Role Gate\n(requireAnyRole)" <<component>> as gate
    rectangle "Problem Advice\n(RFC-7807)"  <<component>> as prob
  }

  package "API Layer" {
    rectangle "Member Controller"     <<component>> as mctl
    rectangle "Approval Controller"   <<component>> as actl
    rectangle "Earning Rule Controller" <<component>> as ectl
    rectangle "Reward Controller"     <<component>> as wctl
    rectangle "Campaign Controller"   <<component>> as cctl
    rectangle "Fraud Controller"      <<component>> as fctl
  }

  package "Anti-Corruption Clients" {
    rectangle "Member Client"    <<acl>> as mcl
    rectangle "Approval Client"  <<acl>> as acl
    rectangle "Earning Client"   <<acl>> as ecl
    rectangle "Reward Client"    <<acl>> as wcl
    rectangle "Campaign Client"  <<acl>> as ccl
    rectangle "Fraud Client"     <<acl>> as fcl
    rectangle "Upstream Error Handler" <<component>> as err
  }
}

' Inbound
bep --> gw  : HTTPS + employee token
gw  --> res : /api/loyalty/admin/*?userId&roles\n(token verified, identity injected as query params, prefix stripped)
res --> gate : EmployeeIdentity(userId, roles)

' Gate feeds controllers
gate --> mctl
gate --> actl
gate --> ectl
gate --> wctl
gate --> cctl
gate --> fctl

' Controllers -> clients (X-Actor forwarded on writes)
mctl --> mcl
actl --> acl
ectl --> ecl
wctl --> wcl
cctl --> ccl
fctl --> fcl

' Clients -> upstreams
mcl --> core   : members / ledger
acl --> core   : approval-requests (store + confirm)
ecl --> earn   : earn-sources / rules
wcl --> redm   : reward-types / rewards
ccl --> camp   : campaigns / drawings / winners
fcl --> bridge : fraud alerts

' Approval apply-on-confirm
wf  --> actl   : confirm (mTLS + BEP assertion)

' Cross-cutting
mcl ..> err
acl ..> err
ecl ..> err
wcl ..> err
ccl ..> err
fcl ..> err
err ..> prob   : translated status + code
prob --> bep   : RFC-7807 Problem

note bottom of err
  No data tier, no Kafka.
  Approval store lives in loyalty-core.
end note

@enduml
```

### 3.2 Role-Gated Request

The shape shared by every operation: the gateway **Authentication Service** verifies the employee token and injects `userId` + `roles` as query parameters; the resolver reads those two params into an `EmployeeIdentity` (no token, no decode), the controller calls `requireAnyRole(...)` (a `403` if none held; `loyalty-admin` is a wildcard), and on a write the employee `userId` is forwarded as `X-Actor` so the owning service's hash-chained audit records who acted.

<p align="center">
  <img src="../images/level-3-loyalty-admin-bff-rolegate.svg" alt="">
</p>

```plantuml
@startuml level-3-loyalty-admin-bff-rolegate
title C4 Level 3 — loyalty-admin-bff — Role-gated request (rule authoring example)

skinparam sequence {
  ArrowColor       #073661
  LifeLineBorderColor #073661
  ParticipantBorderColor #073661
  ParticipantBackgroundColor #e8f0fe
  ParticipantFontColor #073661
}

participant "Bank Employee Portal" as bep
participant "AWS API Gateway\n+ Authentication Service (Lambda)" as gw
participant "Employee Identity Resolver" as res
participant "Earning Rule Controller" as ctl
participant "Earning Client"       as cli
participant "loyalty-earning"      as earn

bep -> gw : POST /api/loyalty/admin/programs/{id}/rules\n(employee token at the edge)
gw  -> gw : Authentication Service verifies token; strip prefix
gw  -> res : POST /programs/{id}/rules?userId&roles\n(identity injected as query params)
activate res
res -> res : read userId + roles request params\n (no token, no decode)
alt missing / blank userId param
  res --> bep : 401 Problem {code=UNAUTHORIZED}
end
res -> ctl : EmployeeIdentity(userId, roles)
deactivate res
activate ctl
ctl -> ctl : requireAnyRole(campaign-manager)
alt none held (and not loyalty-admin)
  ctl --> bep : 403 Problem {code=FORBIDDEN}
end
ctl -> cli : createRule(actor=userId, programId, req)
cli -> earn : POST /programs/{id}/rules  (X-Actor=userId)
earn -> earn : validate DSL; INSERT rule (DRAFT);\n hash-chained audit (X-Actor)
earn --> cli : 201 EarningRule {status=DRAFT}
cli --> ctl : EarningRule
ctl --> bep : 201 Created {status=DRAFT}
deactivate ctl

note over ctl, earn
  Reads gate on readonly+; writes gate on the functional role.
  X-Actor carries the operator id for the owning service's audit.
end note

@enduml
```

**Why this design:**

- **Gate at the edge, scope upstream** — the BFF enforces the *role* (cheap, read straight from the injected `roles` param); per-Program *scope* (PROGRAM_ADMIN) is enforced by the owning service that holds the data.
- **`loyalty-admin` is a wildcard** — break-glass / platform-admin access without enumerating every functional role.
- **`X-Actor`, not a token, crosses the wire** — the owning service audits the employee `userId`; no token is ever forwarded or parsed downstream.
- **Query params, not body or headers** — the actor must gate `requireAnyRole(...)` on every endpoint including GET reads (no body), and a `HandlerMethodArgumentResolver` cannot read the JSON body without breaking `@RequestBody`; query params apply uniformly across GET and POST/PATCH.

### 3.3 Approval Lifecycle

Loyalty does **not** implement maker-checker. Economic changes are **raised** as `PENDING`, the bank's **BEP Approval Workflow** decides out of band, and `confirm` **applies** the change. Because the BFF is stateless, the approval-request store and the apply-on-confirm orchestration live in `loyalty-core`; the BFF forwards. A direct economic write that bypasses the flow (e.g. activating a reward without an approval ref) is rejected by the owning service and translated at the edge.

<p align="center">
  <img src="../images/level-3-loyalty-admin-bff-approval.svg" alt="">
</p>

```plantuml
@startuml level-3-loyalty-admin-bff-approval
title C4 Level 3 — loyalty-admin-bff — Approval lifecycle & upstream activation gate

skinparam sequence {
  ArrowColor       #073661
  LifeLineBorderColor #073661
  ParticipantBorderColor #073661
  ParticipantBackgroundColor #e8f0fe
  ParticipantFontColor #073661
}

participant "BEP (maker)"        as maker
participant "Approval Controller" as actl
participant "Approval Client"     as acl
participant "loyalty-core\n(approval store)" as core
participant "owning service\n(redemption / earning)" as owner
participant "BEP Approval Workflow" as wf

== Raise (PENDING) ==
maker -> actl : POST /approval-requests?userId&roles {type, payload}\n(identity injected as query params by the gateway Auth Service)
activate actl
actl -> actl : requireAnyRole(cs-maker, campaign-manager)
actl -> acl : create(actor=userId, req)
acl -> core : POST /approval-requests (X-Actor=userId)
core --> acl : 201 {requestId, status=PENDING}
acl --> actl : ApprovalRequest
actl --> maker : 201 Created {status=PENDING}
deactivate actl

== BEP decides (out of band) ==
wf -> wf : route by Job Role; enforce 4-eyes + caps; approve

== Confirm (apply / reject) ==
wf -> actl : POST /approval-requests/{id}/confirm\n{decision=APPROVED, bepApprovalRef}\n(mTLS + BEP assertion)
activate actl
actl -> acl : confirm(requestId, req)
acl -> core : POST /approval-requests/{id}/confirm
activate core
alt decision = APPROVED
  alt type = ADJUSTMENT
    core -> core : write Adjusted ledger entry (atomic)
  else RULE_ACTIVATION / REWARD_CHANGE / TIER_CHANGE / TCS_VERSION
    core -> owner : apply via hardened confirm seam (bepApprovalRef)
    owner --> core : applied
  end
  core --> acl : 200 {status=APPLIED, appliedRef}
else REJECTED / not PENDING
  core --> acl : 200 {status=REJECTED} | 409 {code}
end
deactivate core
acl --> actl : ApprovalRequest
actl --> wf : 200 OK
deactivate actl

== Direct economic write WITHOUT approval ==
maker -> actl : PATCH /rewards/{id} {status=ACTIVE}
note right of owner
  owning service rejects:
  409 {code=MISSING_APPROVAL}
  -> translated at the edge
end note

@enduml
```

**Why this design:**

- **BEP owns maker-checker; Loyalty stores a reference** — the bank's workflow already implements Job Roles, 4-eyes, and caps. Re-implementing them in Loyalty would duplicate (and risk diverging from) bank policy.
- **Apply-on-confirm lives in core, not the BFF** — keeps the BFF stateless and puts the atomic ledger write next to the ledger.
- **The activation gate is enforced upstream** — the owning service rejects an un-approved economic change, so the gate can't be bypassed by calling the BFF directly.

---

## 4. Component Inventory

| # | Component | Concern | Writes | Reads | Triggered by |
|---|---|---|---|---|---|
| 1 | **Employee Identity Resolver** | Identity | — | — | Every request; reads gateway-injected `userId` + `roles` query params |
| 2 | **Role Gate** | AuthZ | — | — | Each controller method (`requireAnyRole`) |
| 3 | **Member Controller** | API (Members) | — | — | HTTPS via API Gateway |
| 4 | **Approval Controller** | API (Approvals) | — | — | HTTPS via API Gateway; confirm via BEP Approval Workflow |
| 5 | **Earning Rule Controller** | API (Earning Rules) | — | — | HTTPS via API Gateway |
| 6 | **Reward Controller** | API (Rewards) | — | — | HTTPS via API Gateway |
| 7 | **Campaign Controller** | API (Campaigns) | — | — | HTTPS via API Gateway |
| 8 | **Fraud Controller** | API (Fraud) | — | — | HTTPS via API Gateway |
| 9 | **Member Client** | ACL (→ `loyalty-core`) | — | — | Member Controller |
| 10 | **Approval Client** | ACL (→ `loyalty-core`) | — | — | Approval Controller |
| 11 | **Earning Client** | ACL (→ `loyalty-earning`) | — | — | Earning Rule Controller |
| 12 | **Reward Client** | ACL (→ `loyalty-redemption`) | — | — | Reward Controller |
| 13 | **Campaign Client** | ACL (→ `loyalty-campaign`) | — | — | Campaign Controller |
| 14 | **Fraud Client** | ACL (→ `loyalty-integration-bridge`) | — | — | Fraud Controller |
| 15 | **Upstream Error Handler** | Cross-cutting | — | — | Any `RestClient` 4xx/5xx |
| 16 | **Problem Advice** | Cross-cutting | — | — | Any `BffException` |

All components are **stateless**; none owns a table or a topic.

---

## 5. No Data Tier

`loyalty-admin-bff` owns **no database and no Kafka topic**:

- **The approval-request store lives in `loyalty-core`** — the BFF forwards raise/list/confirm. This keeps the BFF stateless and puts the apply-on-confirm ledger write next to the ledger.
- **Audit trails live in the owning services** — each backend hash-chains its own BEP-originated writes, attributed by the forwarded `X-Actor`.
- **Fraud alerts live in `loyalty-integration-bridge`** — the BFF reads them; it does not consume `loyalty.fraud.alert.v1` itself.

---

## 6. External Edges Re-exposed from L2

| Direction | Counterparty | Mechanism | Triggers which component |
|---|---|---|---|
| Sync inbound | Bank Employee Portal (via AWS API Gateway + Authentication Service) | REST/JSON; token verified at the gateway, identity injected as `userId` + `roles` query params | Employee Identity Resolver → Role Gate → Controllers |
| Sync inbound | BEP Approval Workflow | REST/JSON, mTLS + BEP assertion | Approval Controller (`confirm`) |
| Sync outbound | `loyalty-core` | REST/JSON via mTLS | Member Client, Approval Client |
| Sync outbound | `loyalty-earning` | REST/JSON via mTLS | Earning Client |
| Sync outbound | `loyalty-redemption` | REST/JSON via mTLS | Reward Client |
| Sync outbound | `loyalty-campaign` | REST/JSON via mTLS | Campaign Client |
| Sync outbound | `loyalty-integration-bridge` | REST/JSON via mTLS | Fraud Client |

---

## 7. Invariants & Cross-References

- **Aggregation only — no datastore, no Kafka.** State lives in the upstreams (approvals in core, audit in owning services, fraud in the bridge).
- **Role-gated at the edge, scope enforced upstream.** `requireAnyRole(...)` reads the gateway-injected `roles` param; per-Program PROGRAM_ADMIN scope is the owning service's concern. `loyalty-admin` is a wildcard. The BFF does no token handling.
- **No maker-checker in Loyalty.** Raise → BEP decides → confirm applies; Loyalty stores only `bepApprovalRef`. The activation gate is enforced upstream, so it cannot be bypassed.
- **`X-Actor` carries operator identity for audit** — every write is attributable in the owning service's hash-chained log.
- **Upstream errors are translated, not leaked** — same status + RFC-7807 `code` (e.g. `MISSING_APPROVAL`, `DSL_INVALID`, `CAP_EXCEEDED`).

**Deferred / wired-through:** employee-token verification (the gateway Authentication Service — the BFF trusts the injected `userId` + `roles`); the mTLS + BEP approval assertion on the hardened `confirm` seam (the `bepApprovalRef` is forwarded, not cryptographically verified here); mTLS wiring (cluster infra).

> **Upstream dependency notes.** (a) The **approval store + apply-on-confirm orchestration lives in `loyalty-core`** so the BFF stays aggregation-only. (b) **Fraud alerts are read from `loyalty-integration-bridge`**, which owns the Velocity-Anomaly consumer of `loyalty.fraud.alert.v1` (there is no separate `loyalty-fraud` container) — a deliberate refinement of the L2 async diagram's direct `MSK → admin-bff` arrow.

This is the last of the C4 Level 3 component views. See [`docs/Digital-Loyalty-Arch.md` §10.3](../enterprise-architect.md#103-c4-level-3--component-diagrams--delivered) for the index.

---

*End of document.*
