# Open items — v1 earning vocabulary

Tracking items deliberately deferred or pending external input. Each row names the question, who owns the answer, what it blocks, and the planned resolution path. None of these block the v1 architecture as committed; they block specific content of mapping files, sub-field enrichment, and one DSL primitive.

## 1. Cross-team — Payment Hub enum vocabulary

> **⚠️ DISSOLVED.** OI-PH-1..4 and OI-T24-1 below chased each producer's *native* enum vocabulary so the Bridge could translate it. Under the inverted model, the Host Bank's **adapter** conforms to the **Loyalty-authored contract** (`loyalty.ingress.*`), so a bank's native vocabulary is the adapter author's concern, not a Loyalty open item. These rows are retained for historical context only. **Replacement deliverable:** publish the Loyalty Integration Contract to host-bank integration teams, and per deployment build/verify the bank-side adapter (e.g. HBP card-spend adapter, pending the bank's native card event).

| ID | Question | Owner | Blocks |
|---|---|---|---|
| OI-PH-1 | What are the concrete enum values Payment Hub emits for `paymenthub.payment_completed.v1`'s new `paymentType` field? (i.e. what fills the `<PH_value_for_*>` placeholders in `docs/bridge-translation/*.mapping.yaml`) | Payment Hub team | Final Bridge mapping-file content. Does **not** block code structure — the Bridge ships with placeholder mapping plus a green CI check; mapping content is a config-only change once PH confirms. |
| OI-PH-2 | Does Payment Hub populate `billerCategory` (utility / telecom / tax / etc.) on bill-payment events? At what granularity? | Payment Hub team | The optional `billerCategory` sub-field on `paymenthub.payment_completed.v1` and `biller-category.mapping.yaml`. Five v1 Earn Sources don't require it — they can be authored on `paymentType + amount + currency` alone. |
| OI-PH-3 | Does Payment Hub flag the recipient relationship on P2P transfers (`OWN_ACCOUNT` / `OWN_CUSTOMER` / `OTHER_CUSTOMER` / `EXTERNAL_BANK`)? At what fidelity (binary same-bank-or-not vs full four-way)? | Payment Hub team | Stronger anti-self-transfer-farming defence on `FUND_TRANSFER`. v1 fallback: P2P is excluded wholesale via the FIRST-hit row on `paymentType: P2P_TRANSFER`, which is sufficient — finer-grained discrimination is a post-v1 refinement. |
| OI-PH-4 | Does Payment Hub distinguish top-up target (`MOBILE_AIRTIME` / `E_WALLET` / `PREPAID_CARD`)? | Payment Hub team | The optional `topupTarget` sub-field. v1 defends via `perMemberPerDay` cap on the `TOPUP` rule; sub-field would let DSL exclude `E_WALLET` specifically. |
| OI-T24-1 | What is the concrete enum value the Core Banking Adapter emits on `corebank.account_state.v1`'s `eventType` field for a term-deposit opening? Specifically the string that fills `<core_banking_value_for_term_deposit_opened>` in `t24-event-type-to-earn-source.mapping.yaml` (filename retains `t24-` prefix as a v1 HBP-deployment artefact). | Core Banking Adapter team (v1 HBP deployment: T24 Adapter team) | Final content of `t24-event-type-to-earn-source.mapping.yaml`. Does **not** block code structure — Bridge ships with placeholder; content is a config-only PR once the producer team confirms. |

**Resolution path:** One Slack/email thread per upstream team (PH + Core Banking Adapter) with the questions above; expected ~few-day turnaround each. Each answer is a YAML edit + CI re-validation, no code change.

## 2. Scope — deferred Earn Source families

These are **deliberately out of v1 scope**; listed here so they aren't forgotten.

| ID | Family | What it adds |
|---|---|---|
| OI-DEF-1 | Engagement events (`OPT_IN_BONUS`, `PROFILE_COMPLETED`, `TUTORIAL_COMPLETED`, `EKYC_COMPLETED`, eventually `DAILY_CHECKIN`) | Mobile BFF as second producer to `loyalty.earn.translated.v1`; `loyalty.member.lifecycle.v1` extension with `MEMBER_OPTED_IN` / `MEMBER_REACTIVATED`; `loyalty-earning` consumer for the lifecycle topic. **DSL lifetime cap**: use existing `perMemberPerRule: 1` primitive (the pattern used by `welcome-bonus-onetime.json` — see [sample-dsl-library.md §2.7](sample-dsl-library.md)); no DSL grammar change needed. |
| OI-DEF-2 | State-derived earning (`BALANCE_THRESHOLD` proper semantics + `MIN_BALANCE_HELD` aggregation) | Core Banking Adapter `AccountBalanceSnapshot` cadence verification; `loyalty-earning` scheduled job emitting synthetic events on the canonical topic with deterministic `eventId = hash(memberId + source + windowKey)`. |
| OI-DEF-3 | Referral subsystem (`REFERRAL_COMPLETED`) | Two-phase `referral_pending` table; validator job confirming referee completes eKYC + first qualifying transaction within N days; anti-loop / anti-collusion rules. Likely a separate context, not an Earn Source. |
| OI-DEF-4 | Card-spend pre-settle pending balance | Two-state `Pending → Cleared` Ledger entries with `CardAuthorized` triggering the pending entry; only reopen if Cambodia chargeback rates prove materially higher than v1 baseline assumes. |
| OI-DEF-5 | Active-inquiry contingency service (`loyalty-integration-poller`) | Separate stateful service polling upstreams that emit no events. Not needed for the v1 HBP deployment (all v1 producers emit events). May be needed for future Host Bank deployments. |

## 3. Detailed design

| ID | Item |
|---|---|
| OI-DD-1 | **Latency SLO per-hop budget**: the ≤ 30s p99 end-to-end target (producer emit → balance visible) is pinned. Detailed-design follow-up: allocate sub-budgets to Kafka MSK lag, Bridge translation, Rule Engine evaluation, Ledger insert, Member projection update. Drives Prometheus SLI targets and alert thresholds. |
| OI-DD-2 | **Push-notification on `CardAuthorized`**: product-side decision and `notification-service` integration to deliver the "ting-ting" UX in lieu of a pre-settle ledger state. Not a Loyalty-platform change; coordinated with the Mobile App + notification team. |

## Updating this document

Each item resolves into either a YAML/SQL config change (most of §1 + §2) or a detailed-design follow-up (§4). When an item is fully resolved, **delete the row** rather than marking it done — the live state of this file is "what's still open" only.
