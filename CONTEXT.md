# Rochallor Loyalty Platform

Domain glossary for a digital loyalty platform deployed as a satellite to a **Host Bank Platform (HBP)**. The platform is built once and deployed per host bank — per-deployment identity (legal name, country, central bank, core banking vendor, IAM vendor, mobile app brand, seed Program name) lives in `/deployments/<bank>/DEPLOYMENT.md`, not here. This file is a vocabulary, not a spec — implementation details live elsewhere (the EA document, code).

## Language

**Host Bank Platform (HBP)**:
The host bank's existing digital banking platform that Loyalty is deployed as a satellite to: core banking system, Payment Hub, Mobile Banking App, Customer Service, Authentication Service, notification service, and the supporting cloud infrastructure. The Loyalty platform reuses HBP capabilities and never duplicates them. "HBP" is a **role, not a brand name** — every deployment binds HBP to a specific institution's platform (see `/deployments/<bank>/DEPLOYMENT.md` for the v1 deployment).
_Avoid_: using "HBP" as a synonym for the institution's name; using vendor names (e.g. "T24", "Keycloak") in domain prose — those are deployment-specific.

**Host Bank**:
The institution that owns the HBP this Loyalty deployment runs against. Used as the subject when the institution's identity is irrelevant ("a Host Bank Customer"). Each deployment's `DEPLOYMENT.md` supplies the concrete institution name.
_Avoid_: "the Bank" (ambiguous), "the client" (implies vendor relationship), institution-specific names in domain prose.

**Program**:
A self-contained loyalty scheme with its own earning rules, tier ladder, reward catalog, and member roster. V1 ships exactly one seeded Program (name set in deployment config — see `DEPLOYMENT.md`) but the model treats `Program` as a first-class aggregate so additional programs (co-brand cards, payroll, partner promos) can be added without rework.
_Avoid_: "scheme", "campaign" (a Campaign is a time-bounded thing *within* a Program — different concept).

**Customer**:
A person who has been onboarded to the host bank (KYC'd) and exists in the HBP as an account holder. Loyalty references a Customer by `customerId` but never owns their PII — HBP is the system of record.
_Avoid_: "user" (too generic), "account holder" (ambiguous with bank account).

**Member**:
A Customer who has opted into a specific Program. One Member exists per `{customerId, programId}` pair. The Member owns enrollment date, opt-in/out status, current tier, current points balance, and loyalty-specific preferences. A Customer can be a Member of multiple Programs; a Member without a Customer is impossible.
_Avoid_: "loyalty customer", "subscriber", "participant".

**Aggregate Root**:
The consistency boundary loaded, mutated, and saved as one unit inside a single transaction. **Member is the aggregate root of the points-posting boundary**: it owns its `redeemable_balance` / `qualifying_balance` / `currentTierCode` projections and its open Point Cohorts, it is the only thing that produces new Point Ledger Entries (preserving invariant P5 and Ledger immutability), and it records Domain Events for the outbox rather than publishing them itself. The posting root holds *current state plus the entries appended in this transaction* — never the full Ledger history (which is unbounded; balance and cohorts are projections, not a replay). **Reservation is a separate aggregate** (its own lock and TTL lifecycle); the bridge between them is the **Redeemable Balance** calculation, expressed as a pure function of `redeemable_balance` minus the active-HELD total.
_Avoid_: treating a Reservation as part of the Member aggregate; loading the whole Point Ledger to mutate a Member.

**Opt-in**:
The act by which a Customer becomes a Member of a Program. **In v1 any HBP Customer can opt-in** — no eligibility gate. Requires explicit acceptance of the current Program T&Cs version. A Member who has previously opted out and opts in again is the *same* Member (lifecycle event = `Reactivated`) — not a new Member.

**Eligibility Status**:
A per-Member-per-Program field reserved as an extension point for future BI-driven eligibility evaluation. Defaults to `ELIGIBLE` for all Members in v1. If BI integration is added later (v1.x+), the field will be driven by a batch-fed eligibility feed; ineligible Members may be restricted from earning new points by Program policy, but existing Redeemable Balance and Tier are never affected. Not active in v1.

**T&Cs Version**:
A monotonically-increasing version number on a Program's Terms & Conditions. Each Member's record stores the `tcs_version_accepted` at the time of opt-in. When the Program's `current_tcs_version` advances, Members are flagged for re-acceptance: a 30-day grace window during which they can still earn but cannot redeem; after grace, all features are restricted until re-acceptance.

**Point**:
The unit of loyalty value within a Program, denominated as an integer. A Member's Point holdings are determined by their entries in the Point Ledger. Points are *not* fungible across Programs.
_Avoid_: "reward currency", "loyalty token", "miles" (unless a future Program defines a Mile-based unit, in which case it gets its own term).

**Point Ledger**:
The append-only record of all Point movements for Members of a Program. Each Point Ledger Entry is immutable: corrections happen by appending a compensating entry, never by mutating an existing one. Source of truth for a Member's points balance.
_Avoid_: "points table", "transactions" (overloaded with banking transactions).

**Point Ledger Entry**:
One immutable row in the Point Ledger. Carries: `memberId`, `programId`, `qualifying_delta` (signed integer, contributes to tier qualification), `redeemable_delta` (signed integer, contributes to spendable balance), `entryType` (e.g. `Earned`, `Redeemed`, `Expired`, `Reversed`, `Adjusted`), `sourceRef` (idempotency key tying the entry back to its originating real-world event), `reason`, `createdAt`. The pair `(sourceRef, entryType)` is unique — replays are silently no-ops. Standard entry-type effects: `Earned`(+N,+N), `Redeemed`(0,−N), `Expired`(−N,−N), `Reversed`(−N,−N), `Adjusted`(configurable per entry).

**Redeemable Balance**:
A Member's current spendable Point holdings: `SUM(redeemable_delta)` over all of their Ledger Entries. This is the balance customers see in the mobile app and the one that funds redemptions. Maintained as denormalized column `Member.redeemable_balance`.

**Qualifying Balance**:
A Member's Tier-progress counter: `SUM(qualifying_delta)` over their Ledger Entries within the Program's configured Qualifying Window (lifetime / rolling 12 months / calendar year). Redemptions never reduce it; expiry and earn reversals do. Maintained either as a denormalized column or via a windowed query depending on the Qualifying Metric — implementation decision deferred.

**Tier**:
A ranked level within a Program (e.g. Bronze, Silver, Gold) that a Member occupies based on their Qualifying Balance crossing configured thresholds. Tiers carry per-tier benefits (e.g. earn multiplier, exclusive Rewards, fee waivers). The Tier ladder is a Program-scoped configurable list, *not* a hardcoded enum. The v1 seed Program has 3 tiers (see `DEPLOYMENT.md` for seed values).
_Avoid_: "level", "rank", "status" (last one is overloaded with member opt-in status).

**Qualifying Metric**:
The Program-scoped configuration that decides how Qualifying Balance is computed over time. One of `LIFETIME` (cumulative forever — tier is sticky), `ROLLING_12_MONTHS` (trailing 12-month window — tier re-evaluated on each Member's anniversary), or `CALENDAR_YEAR` (annual reset). Default for v1: `ROLLING_12_MONTHS`.

**Tier Ladder**:
The ordered list of Tiers configured for a Program, with their thresholds and benefits. Editable by BEP admins. Adding a new Tier is config; removing one with active Members requires a documented migration plan.

**Reward**:
A Program-scoped configurable item that a Member can exchange Points for, authored in BEP. Carries: reward type, point cost, fulfillment parameters (e.g. cashback amount + currency, voucher template, partner SKU), eligibility (Tier-gated, segment-gated, tenure-gated, currency-gated, per-Member cap), inventory (optional cap), validity window. The platform ships with a fixed catalog of Reward Types; specific Rewards are admin-created instances of those types.
_Avoid_: "offer" (overloaded with marketing offers), "perk", "benefit" (Tier benefits ≠ Rewards).

**Reward Type**:
A registry entry representing a *category* of Reward identified by a `rewardTypeCode`. Each Reward Type is bound to one Fulfillment Adapter and declares the parameter shape Rewards of that type must supply. The platform ships v1 with the following Reward Types: `CASHBACK_TO_CASA`, `BILL_PAYMENT_VOUCHER`, `THIRD_PARTY_VOUCHER`, `SWEEPSTAKES_ENTRY`, `MATERIAL_GOODS`, `CHARITY_DONATION`, `TIER_BOOST`. (`FEE_WAIVER` is **deferred from v1** — requires a Fee Engine integration on the Host Bank side; will be added when a deployment requires it. `PAY_WITH_POINTS` is also not in v1; deferred pending Payment Hub-side support.) Whether a given deployment enables a shipped Reward Type is a deployment-config decision (see `DEPLOYMENT.md`).

**Fulfillment Adapter**:
The integration component owning the call to whatever external system actually delivers value for one Reward Type. Standardized interface: `reserve()`, `commit()`, `release()` — supporting the two-phase redemption pattern. Examples: `CashbackAdapter` calls Payment Hub disbursement to credit Customer CASA; `ThirdPartyVoucherAdapter` calls a partner API to provision a voucher code; `SweepstakesAdapter` writes a Drawing Entry.

**Redemption**:
The act of a Member exchanging Points for a Reward. Always two-phase: `reserve()` → `commit()` (success) or `reserve()` → `release()` (fulfillment failed or TTL expired). Reservations live in a separate `point_reservation` table — they are *not* Ledger Entries (which would violate immutability for a transient state). Only `commit()` writes a permanent `Redeemed` Point Ledger Entry.

**Reservation**:
A short-lived hold on a Member's Redeemable Balance and (if applicable) Reward inventory, created by `reserve()` and consumed by `commit()` or `release()`. Has a configurable TTL per Reward Type (default 15 min, longer for slow partner adapters). Stored as a row in `point_reservation` with status `HELD | COMMITTED | RELEASED`. A stale `HELD` reservation past TTL is auto-released by a sweeper job. The Effective Redeemable Balance shown to a Member = `Member.redeemable_balance` (ledger projection) − `SUM(reservedPoints WHERE status = HELD AND not expired)`.

**Drawing**:
A time-bounded random-selection event used by `SWEEPSTAKES_ENTRY` Rewards. A Drawing accepts Entries (created when a Member redeems for a sweepstakes Reward), runs a defined selection process at the configured drawing time, and produces Winners. Belongs to the Campaign subsystem.

**Expiry**:
The policy by which earned Points lose their value if not redeemed within a Program-defined duration. The platform uses **fixed expiry from earn date with FIFO consumption**: each `Earned` Ledger Entry carries `expires_at = earned_at + programExpiryMonths`, and the value is *snapshotted at earn time* (changes to Program config never retroactively affect already-earned cohorts). Redemption consumes oldest cohorts first. A nightly job per Program writes `Expired` Ledger Entries for unconsumed expired cohorts. Default `programExpiryMonths` for the v1 seed Program = 24 (see `DEPLOYMENT.md` for the deployment-specific value).

**Tier Expiry Override**:
A per-Tier opt-in that overrides the Program's default expiry duration (or disables expiry entirely) for Members in that Tier. Example: Bronze inherits 24 months, Silver = 36 months, Gold = no expiry. Configured in BEP per Tier.

**Point Cohort**:
The set of Points originating from a single `Earned` Ledger Entry, tracked by an auxiliary `point_cohort` projection used by the FIFO consumption algorithm. Each Cohort tracks `entryId`, `original_amount`, `consumed_amount`, `expires_at`, `expired_amount`. Cohorts are not source-of-truth — they are a projection rebuildable from the Ledger.

**Maker-Checker** (4-eyes):
The mandatory two-person workflow for any manual Point adjustment via BEP. A *maker* (CS rep / case agent) creates an adjustment request specifying the Member, amount, direction, and reason. A *checker* (CS supervisor / authorized approver) reviews and approves. Only on approval does the `Adjusted` Point Ledger Entry get written, with both `maker_user_id` and `checker_user_id` populated. Adjustment size caps per maker role are configurable in BEP.

**Manual Adjustment**:
A Point Ledger Entry of type `Adjusted` written via the Maker-Checker workflow rather than via Earning/Redemption flows. Carries the maker, checker, business reason, and a supporting case reference. Used for: customer-service goodwill credits, clearing small negative balances, regulatory corrections, dispute resolution.

**Negative Redeemable Balance**:
A Member's `redeemable_balance` falling below zero, occurring when an upstream reversal (e.g. `PaymentReversed`) claws back points the Member has already spent. The Member's account remains active but cannot redeem until balance ≥ 0 again (either by earning, or by a goodwill `Adjusted` entry through Maker-Checker). The Ledger invariant `balance = sum(deltas)` is preserved — going negative is explicitly allowed.

**Service Audit Log**:
A per-service `<service>_audit_log` table recording every administrative write performed via BEP: `{actor_iam_id, action, entity_type, entity_id, before_json, after_json, occurred_at}`. Distinct from the customer-facing Point Ledger; this is for operational governance of admin actions. Retention ≥ 7 years (or as required by the Central Bank, whichever is longer).

## Bounded contexts and services

Loyalty is organized into 8 bounded contexts deployed as 7 services (hybrid grouping):

| Service | Bounded contexts hosted |
|---|---|
| `loyalty-core` | Membership + Ledger (share DB transaction boundary) |
| `loyalty-earning` | Earning (Earn Source registry, Rule Engine, DSL eval) |
| `loyalty-redemption` | Reward catalog + Fulfillment Adapters (plug-in style) |
| `loyalty-campaign` | Campaign + Drawing |
| `loyalty-integration-bridge` | Kafka consumer/producer translation layer |
| `loyalty-mobile-bff` | Mobile-facing read/write APIs for the Mobile Banking App |
| `loyalty-admin-bff` | BEP-facing admin APIs (CRUD on Programs, Rules, Rewards, Campaigns, etc.) |

Reused from the Host Bank Platform (NOT built by Loyalty): Authentication Service, notification service, Customer profile / PII (Core Banking Adapter), Payment Hub, Core Banking Adapter, BI/analytics tooling, regulatory reporting tooling.

**Domain Event**:
A typed, versioned, semantically-named business fact emitted by a producing system (e.g. Payment Hub, Core Banking Adapter) onto Kafka — for example `PaymentCompleted`, `PaymentReversed`, `CardSpendPosted`. Owned and versioned by the producer; consumed by Loyalty (and other downstream subscribers). The contract is the event schema, not the producer's database.
_Avoid_: "CDC event" (different pattern — see below), "message", "kafka event" (mechanism, not domain concept).

**Bank-specific Producer Event**:
A Domain Event on a Host Bank's **own native stream**, whose payload schema, vocabulary, and Kafka topic are owned by the bank (e.g. HBP's `ph.transaction.transaction-event`). **Loyalty no longer consumes these directly**: they are the *input* to a **bank-side adapter** that translates them into [Ingress Events](#) conforming to the **Loyalty Integration Contract**. The adapter is owned by the deployment/integration team, lives outside the Loyalty repo, and absorbs all bank-specific quirks (step-event de-duplication, `accountNumber → customerId` resolution, `traceparent` propagation).
_Avoid_: treating a bank's native topic/vocabulary as something Loyalty parses — it is the adapter's concern, never Loyalty's.

**Loyalty Integration Contract**:
The **Loyalty-authored, versioned set of inbound channels** (`loyalty.ingress.*`) plus their JSON Schemas that every Host Bank deployment must produce to in order to integrate (spec at `docs/asyncapi/loyalty-ingress.yaml`). It is the inversion of the older "producer owns the schema" stance: portability across banks comes from every bank speaking *this* contract, not from Loyalty absorbing each bank's native shape.
_Avoid_: "external-consumed contract" (that file is now only per-deployment documentation of a bank's native stream).

**Ingress Event**:
A **Layer-1, customer-scoped** event a bank-side adapter produces on a `loyalty.ingress.<source>.v1` channel (e.g. `loyalty.ingress.card_spend.v1`). One channel per Earn Source — self-describing, so the bank need not know Loyalty's `earn_source_code` taxonomy. Carries **`customerId`** and is **keyed on `customerId`**; never `memberId` or `programId`. `loyalty-integration-bridge` validates it, stamps the canonical `source`, and re-emits a Canonical Loyalty Event (Layer 2).
_Avoid_: putting `memberId`/`programId` on an Ingress Event — member and program resolution is `loyalty-earning`'s job.

**Canonical Loyalty Event**:
A Domain Event whose **schema, channel name, and enum vocabulary are owned by Loyalty** (e.g. `loyalty.earn.translated.v1`, `loyalty.member.lifecycle.v1`, `loyalty.payment.reversed.v1`). Produced by `loyalty-integration-bridge` after **validating an Ingress Event and stamping the canonical `source`**, or by Loyalty services directly. The set of acceptable values for each canonical field (`source`, `paymentType`, `lifecycleType`, etc.) is the **canonical vocabulary** the Rule Engine, DSL, BEP UI, and reporting all share. The customer→`earn_source_code` step is Loyalty-owned and bank-uniform; it is no longer a per-bank `*.mapping.yaml` seam (that translation moved bank-side).
_Avoid_: introducing canonical event names with a producer brand in them.

**Earn Event**:
A Domain Event that Loyalty's Earning Rule Service evaluates as a candidate for awarding Points. Not every Domain Event is an Earn Event — it depends on the Program's configured earning rules. An Earn Event's `eventId` becomes the `sourceRef` on the resulting Point Ledger Entry, guaranteeing idempotency on replay.

**Earn Source**:
A registry entry representing a category of activity that *can* award Points under some Program. Identified by an `earnSourceCode`. The **v1 catalog is exactly five active sources**: `CARD_SPEND` (post-settle card spend from Payment Hub), `BILL_PAYMENT` / `FUND_TRANSFER` / `TOPUP` (the three discriminated subtypes of the producer-side `payment_completed.v1` event via the canonical `paymentType` field — `P2P_TRANSFER` and `QR_PAYMENT` both route to `FUND_TRANSFER` and are discriminated by DSL `when` clauses), and `TERM_DEPOSIT_OPENED` (from the Core Banking Adapter). Plus `MANUAL_ADJUSTMENT` (via Maker-Checker, not event-driven). `PAYMENT_COMPLETED` is seeded as **inactive fallback only** to absorb unmapped producer `paymentType` values. Engagement (`OPT_IN_BONUS`, `PROFILE_COMPLETED`, `EKYC_COMPLETED`, `DAILY_CHECKIN`), state-derived (`MIN_BALANCE_HELD`, `BALANCE_THRESHOLD`), and `REFERRAL_COMPLETED` are **deferred from v1** — each will be reintroduced when the underlying state-machine / scheduling / two-phase-validation design is committed. Each Earn Source declares the Domain Event type(s) it observes. Adding a brand-new Earn Source for a Domain Event type not yet supported requires a small engineering task (event subscription + DSL primitive if needed); activating an existing one is config-only.
_Avoid_: "earning channel", "source type" (too generic), "event source" (overloads with event-sourcing).

**Earning Rule**:
A Program-scoped configuration row authored in BEP that says: "for events from this Earn Source matching these conditions, award Points via this formula, capped by these limits, during this effective period." Stored as JSON (constrained DSL) plus metadata. Authoring UX is a decision-table editor backed by the same DSL. Multiple Earning Rules per Earn Source per Program are allowed (e.g. base rate + partner-merchant bonus).

**Rule Engine**:
The internal interpreter inside the Earning Service that evaluates an incoming Earn Event against the set of active Earning Rules for the relevant Program, computes the Points to award, and emits a request to the Ledger Service. Stateless except for rule cache and rate-limit / cap counters.

**Rule Conflict**:
The condition where a single Earn Event matches more than one active Earning Rule under the same Program. Conflicts are resolved by **summing** — every matching rule fires and contributes its own Point Ledger Entry. Each entry carries `sourceRef = eventId + ruleId` so multi-rule fires are individually auditable and individually reversible.

**Source-Aggregate Cap**:
An optional cap defined at the Earn Source level (per Program) that limits the total Points awardable across *all* Earning Rules for that source over a window (e.g. `dailyCap: 1000 points` on `CARD_SPEND` regardless of how many rules fire). Distinct from a Rule Cap which limits a single rule. When both are configured, the more restrictive applies.

**DSL** (in this project specifically):
The constrained JSON expression language used to author Earning Rule conditions and earn formulas. Not Turing-complete. Vocabulary is a fixed set of primitives (e.g. `amount`, `merchantCategory`, `dayOfWeek`, `eventType` for conditions; `pointsPerUnit`, `flatPoints`, `multiplier` for actions; `dailyCap`, `monthlyCap`, `lifetimeCap`, `oncePerMember` for caps). New primitives are added by Loyalty engineering when a real new pattern emerges.

## Invariants

- **Ledger immutability**: no UPDATE or DELETE on Point Ledger Entries, ever. Corrections = new compensating entry.
- **Idempotency-key required**: every Point Ledger Entry carries a `sourceRef`. The `(sourceRef, entryType)` tuple is unique. Earning attempts without a stable source ID are rejected.
- **Single writer to Member.points_balance**: only the Ledger Service writes the balance column, and only in the same DB transaction as the corresponding ledger insert.

## Flagged ambiguities

- **Points-on-offboarding**: when a Customer closes their bank account, what happens to their points? Forfeit immediately vs. grace period vs. cash-out window. Deferred to a later question.
