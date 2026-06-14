# Rochallor Loyalty Platform — Data Catalogue

> **Artifact §11.4** of [`enterprise-architect.md`](../enterprise-architect.md#114-supporting-artifacts-to-build).
> The conceptual model in [§5.2](../enterprise-architect.md#52-conceptual-data-model) is the *logical* picture; this catalogue is the **per-service, per-table** physical model with sensitivity classification. The executable migrations live under [§10 (embedded DDL)](#10-embedded-ddl-full-sql) and are validated against PostgreSQL 16.

---

## 1. Scope & sources of truth

The platform persists to **four PostgreSQL RDS instances**, one per backend service. `loyalty-integration-bridge` has **no database** — see [§7](#7-loyalty-integration-bridge--no-rds). The two BFFs are stateless aggregation layers and own no tables (Redis cache only, not catalogued here).

| RDS instance | Bounded contexts | System-of-record for |
|---|---|---|
| `loyalty-core` | Membership + Ledger | Program/Tier config, Member identity + per-Program state, Point Ledger (financial source of truth), cohorts, reservations, adjustments, conversion scaffolding |
| `loyalty-earning` | Earning | Earn Source registry, Earning Rules (DSL), cap counters, event dedup |
| `loyalty-redemption` | Reward + Fulfillment | Reward catalogue, eligibility, inventory, redemption saga state |
| `loyalty-campaign` | Campaign | Campaigns, Drawings, entries, winner records |

**No cross-service joins are physically possible**: a column that names another service's entity (e.g. `point_ledger.rule_id`, `redemption_saga.reservation_id`) is a **logical reference** carried by value, never a SQL foreign key. The cross-service reference map is in [§8](#8-cross-service-logical-reference-map).

**SQL is the runtime source-of-truth.** The Flyway migrations under the embedded DDL in [§10](#10-embedded-ddl-full-sql) are the authoritative DDL — applied to PostgreSQL 16 at service boot. This catalogue is the *reading* artifact: prose semantics, sensitivity classification, rationale, and reconciliation notes. Each per-table sub-section below appends a ` · [DDL ↗]` link to that service's embedded migration in [§10](#10-embedded-ddl-full-sql), so column types and constraints are always one click from the prose.

---

## 2. Conventions

| Concern | Decision | Rationale |
|---|---|---|
| **Surrogate keys** | `BIGINT GENERATED ALWAYS AS IDENTITY` | `member_id` / `program_id` are int64 to match the published API contract. One ID strategy across all tables. |
| **Enumerations** | `VARCHAR` + named `CHECK` constraint | Evolvable without `ALTER TYPE`; the platform is config-driven and these value-sets grow (e.g. `entry_type` already gains `Converted-In/Out`). |
| **Points / money** | signed `BIGINT` (whole points) | Deltas may be negative; `redeemable_balance` may go below zero after clawback. |
| **Timestamps** | `TIMESTAMPTZ`, `DEFAULT now()` on create columns | UTC everywhere; DR spans regions. |
| **Idempotency** | dedicated `UNIQUE` `*_key` / `source_ref` columns | Mobile retries on poor connectivity must not duplicate writes. |
| **Foreign keys** | declared **only within the same RDS** | Cross-service integrity is an eventual/contract concern, not a DB constraint. |
| **Standard tables** | every backend RDS has `outbox` + `shedlock`; every service has `<svc>_audit_log` | Transactional outbox for event publish; ShedLock for multi-Pod `@Scheduled` jobs; per-service audit ≥ 7 yr ([§7.4](../enterprise-architect.md#74-fraud--audit)), hash-chained + DB-immutable + nightly-sealed to S3 Object Lock WORM. |

### Sensitivity legend

Derived from [§5.3 PII & Data Protection](../enterprise-architect.md#53-pii--data-protection). All RDS are KMS-encrypted at rest; all transit is TLS 1.3 / service-to-service mTLS.

| Class | Meaning | Examples in this catalogue |
|---|---|---|
| **None** | Non-sensitive config / operational metadata | `program`, `tier`, `earn_source`, `reward_type` |
| **Low** | Loyalty-internal, no financial or personal exposure | reservations, cohorts, campaign/drawing config |
| **Medium** | Business-logic IP or redemption history | `earning_rule.dsl_json`, `reward`, `redemption_saga` |
| **Financial — High** | Money-equivalent value; audit-critical | `point_ledger`, `member_program` balances, `approval_request` |
| **High (audit)** | Append-only, hash-chained + DB-immutable + WORM-sealed; ≥7yr | `*_audit_log` |
| **Customer PII** | **Never stored here** — referenced by `customer_id` only | (boundary marker) |

> **PII boundary.** No Loyalty table stores name, DOB, NRC, phone, address or email. `member.customer_id` is the only link to the Customer Service, which remains the PII system-of-record ([§5.3](../enterprise-architect.md#53-pii--data-protection), `CONTEXT.md` PII Boundary).

---

## 3. `loyalty-core` RDS  →  [full DDL ↓](#101-loyalty-core)

13 tables. Owns the financial source of truth.

### 3.1 `program` — Program config *(sensitivity: None)*  ·  [DDL ↗](#101-loyalty-core)
System-of-record for Program + Tier. Lifecycle (`create/activate/sunset/close`) is **migration-driven, not BEP-driven**.

| Column | Type | Null | Notes |
|---|---|---|---|
| `program_id` | bigint identity | PK | int64 contract key |
| `program_code` | varchar(32) | UNIQUE | deployment-supplied seed code (e.g. `RDB_REWARDS` for the HBP deployment) |
| `name` | varchar(160) | not null | |
| `status` | varchar(16) | not null | `DRAFT \| ACTIVE \| SUNSET \| CLOSED` |
| `qualifying_metric` | varchar(20) | not null | `LIFETIME \| ROLLING_12_MONTHS \| CALENDAR_YEAR`; v1 default `ROLLING_12_MONTHS` |
| `default_expiry_months` | int | not null | v1 default 24 |
| `current_tcs_version` | int | not null | |
| `enrollment_model` | varchar(28) | not null | `EXPLICIT_OPT_IN \| AUTO_ENROLL_ON_ELIGIBILITY \| AUTO_ENROLL_ALL \| INVITE_ONLY` |
| `eligibility_predicate_json` | jsonb | null | used only for `AUTO_ENROLL_ON_ELIGIBILITY` |
| `source_aggregate_caps` | jsonb | not null | per-Earn-Source aggregate caps |
| `created_at` / `updated_at` | timestamptz | not null | |

### 3.2 `tier_ladder` / `tier` — Tier config *(None)*  ·  [DDL ↗](#101-loyalty-core)
One ladder per Program (`UNIQUE(program_id)`). `tier.ordinal` orders the ladder (0 = entry); `tier.threshold` is the qualifying-point entry bar. `tier.benefits` (jsonb) carries multiplier, exclusive-reward ids, and the **expiry override** (`expiry_override_months` / `never_expire`). FK `tier.ladder_id → tier_ladder`, `tier_ladder.program_id → program`.

### 3.3 `member` — per-Customer identity *(None — PII-free)*  ·  [DDL ↗](#101-loyalty-core)
| Column | Type | Null | Notes |
|---|---|---|---|
| `member_id` | bigint identity | PK | |
| `customer_id` | bigint | UNIQUE | logical FK → Customer Service; **the only PII link** |
| `preferences` | jsonb | not null | channel/notification prefs |
| `created_at` / `updated_at` | timestamptz | not null | |

### 3.4 `member_program` — per-(Member, Program) state *(Financial — High)*  ·  [DDL ↗](#101-loyalty-core)
The `member_program` junction. Composite PK `(member_id, program_id)`; **no `is_primary`** ("primary" is presentation-only, §5). Balances are a **projection** of `point_ledger` (the ledger is source of truth).

| Column | Type | Null | Notes |
|---|---|---|---|
| `member_id`, `program_id` | bigint | PK | FK → `member`, `program` |
| `status` | varchar(16) | not null | `ACTIVE \| SUSPENDED_TCS \| OPTED_OUT \| CLOSED` |
| `redeemable_balance` | bigint | not null | signed; **may be < 0** after clawback; redemption blocked while negative |
| `qualifying_balance` | bigint | not null | tier-progress; debited only by expiry / earn-reversal |
| `current_tier_id` | bigint | null | FK → `tier` |
| `tcs_version_accepted` | int | null | **NULL ⇒ earning gated** — Earn Service silently drops earn |
| `eligibility_status` | varchar(24) | not null | default `ELIGIBLE` |
| `opt_in_at` / `opt_out_at` | timestamptz | null | |

### 3.5 `point_ledger` — append-only Point movements *(Financial — High)*  ·  [DDL ↗](#101-loyalty-core)
**Source of truth**. `balance = SUM(deltas)` per (member, program). No `UPDATE`/`DELETE` — enforced by `trg_point_ledger_immutable` (validated: see [§9](#9-validation)).

| Column | Type | Null | Notes |
|---|---|---|---|
| `entry_id` | bigint identity | PK | |
| `member_id`, `program_id` | bigint | not null | FK → `member_program` (composite) |
| `qualifying_delta`, `redeemable_delta` | bigint | not null | signed; per-`entry_type` semantics |
| `entry_type` | varchar(16) | not null | `Earned \| Redeemed \| Expired \| Reversed \| Adjusted \| Converted-Out \| Converted-In` (last two unused in v1) |
| `source_ref` | varchar(160) | not null | idempotency; **`UNIQUE(source_ref, entry_type)`** |
| `rule_id` | bigint | null | **logical** → `loyalty-earning.earning_rule` (Earned only) |
| `expires_at` | timestamptz | null | snapshotted on Earned |
| `approval_request_id` | bigint | null | set on `Adjusted`; FK → `approval_request`. 4-eyes/caps are enforced by BEP, not Loyalty |
| `reason` | text | null | |
| `created_at` | timestamptz | not null | |

### 3.6 `point_cohort` — FIFO consumption tracker *(Low)*  ·  [DDL ↗](#101-loyalty-core)
Rebuildable projection; one row per Earned entry (`UNIQUE(ledger_entry_id)`, FK → `point_ledger`). Tracks `original_amount / consumed_amount / expired_amount` with `CHECK(consumed + expired ≤ original)`. Partial indexes drive FIFO consumption and the expiry sweep over unconsumed cohorts.

### 3.7 `point_reservation` — two-phase hold *(Low)*  ·  [DDL ↗](#101-loyalty-core)
Transient `HELD \| COMMITTED \| RELEASED`; **not** in the ledger. `held_until` (TTL) drives the sweeper (partial index on `status='HELD'`); `UNIQUE(idempotency_key)`. FK → `member_program`. `reward_id` is **logical** → `loyalty-redemption.reward`.

### 3.8 `approval_request` — generic BEP-approval record *(Financial — High)*  ·  [DDL ↗](#101-loyalty-core)
A proposed change awaiting approval. Generic over `request_type` (`ADJUSTMENT`, `RULE_ACTIVATION`, `REWARD_CHANGE`, `TIER_CHANGE`, `TCS_VERSION`, …); the proposed change lives in `payload` (jsonb). Loyalty owns the *what*; **BEP's existing Approval Workflow owns routing, Job Roles, 4-eyes, and caps** — so there are **no maker/checker columns and no per-role-cap logic here**. Loyalty stores only `bep_approval_ref` (BEP is system-of-record for who approved). `status ∈ {PENDING, APPLIED, REJECTED}`; `applied_ref` is a logical pointer to the resulting artifact (e.g. `point_ledger.entry_id`, no FK to avoid a cycle). Partial index on `PENDING` per `request_type`.

### 3.9 `program_conversion_rule` — reserved, **empty in v1** *(None)*  ·  [DDL ↗](#101-loyalty-core)
Schema scaffold for cross-Program point conversion. Composite PK `(src_program_id, dst_program_id)`, both FK → `program`; `direction ∈ {ONE_WAY, BIDIRECTIONAL}`; positive rate `CHECK`; no self-conversion; `bep_approval_ref` instead of maker/checker columns. Populated only when a second Program defines policy — Program-2 activation is gated on this.

### 3.10 `core_audit_log` / `outbox` / `shedlock`  ·  [DDL ↗](#101-loyalty-core)
Standard tables (see [§2](#2-conventions)). `outbox` carries `loyalty.member.*` + `loyalty.ledger.*`. `shedlock` serialises the Expiry job, Reservation TTL sweeper, outbox janitor, audit-archive flush.

---

## 4. `loyalty-earning` RDS  →  [full DDL ↓](#102-loyalty-earning)

7 tables.

### 4.1 `earn_source` — accepted earn-source registry *(None)*  ·  [DDL ↗](#102-loyalty-earning)  ·  [v1 seed (V2) ↗](#102-loyalty-earning)
`earn_source_id` PK, `earn_source_code` UNIQUE (e.g. `CARD_SPEND`), `domain_event_types` (jsonb list), `active_by_default`. A new Earn Source ⇒ a new translation in the integration bridge. The v1 catalogue (`CARD_SPEND`, `BILL_PAYMENT`, `FUND_TRANSFER`, `TOPUP`, `TERM_DEPOSIT_OPENED` + inactive fallback `PAYMENT_COMPLETED`) is seeded by Flyway migration **`V2__seed_v1_earn_sources.sql`**.

### 4.2 `earning_rule` — per-Program rule in the DSL *(Medium — business IP)*  ·  [DDL ↗](#102-loyalty-earning)
| Column | Type | Null | Notes |
|---|---|---|---|
| `rule_id` | bigint identity | PK | |
| `program_id` | bigint | not null | **logical** → `loyalty-core.program` |
| `earn_source_id` | bigint | not null | FK → `earn_source` |
| `dsl_json` | jsonb | not null | canonical constrained DSL; schema-validated at save; **no Drools/Groovy** |
| `version` | int | not null | |
| `status` | varchar(10) | not null | `DRAFT \| ACTIVE \| ARCHIVED`; never deleted (audit) |
| `effective_from` / `effective_to` | timestamptz | null | windowed; `CHECK(to > from)` |
| `campaign_id` | bigint | null | **logical** → `loyalty-campaign.campaign` |

Hot-path index `(program_id, earn_source_id, status)`.

### 4.3 `cap_counter` — cap accumulation *(Low)*  ·  [DDL ↗](#102-loyalty-earning)
Per `(program_id, rule_id, member_id, window_key)` (UNIQUE). FK → `earning_rule`. Nightly purge over `window_end`. Supports daily / per-Member / per-Source caps.

### 4.4 `idempotency_key` — event dedup *(Low)*  ·  [DDL ↗](#102-loyalty-earning)
`event_id` PK, `processed_at`; TTL-purged after 90 days. Cheap short-circuit before rule work.

### 4.5 `earning_audit_log` / `outbox` / `shedlock`  ·  [DDL ↗](#102-loyalty-earning)
Standard. `outbox` carries `loyalty.earning.PointsEarned`. `shedlock` serialises cap-counter purge, idempotency-key purge, outbox janitor (see reconciliation [R2](#appendix-reconciliation-notes)).

---

## 5. `loyalty-redemption` RDS  →  [full DDL ↓](#103-loyalty-redemption)

8 tables.

### 5.1 `reward_type` — platform config *(None)*  ·  [DDL ↗](#103-loyalty-redemption)
`reward_type_code` PK (e.g. `CASHBACK`, `BILL_VOUCHER`, `THIRD_PARTY_VOUCHER`, `SWEEPSTAKES`), `fulfillment_adapter_class`, `parameter_schema` (jsonb). Binds a reward kind to its Fulfillment Adapter SPI.

### 5.2 `reward` — per-Program catalogue *(Medium)*  ·  [DDL ↗](#103-loyalty-redemption)
`reward_id` PK; `program_id` logical; `reward_type_code` FK → `reward_type`; `reward_revision` (immutable-once-redeemed versioning); `point_cost ≥ 0`; `fulfillment_params` jsonb; `status DRAFT|ACTIVE|ARCHIVED`; validity window `CHECK`. Index `(program_id, status)`.

### 5.3 `reward_inventory` — optional stock cap *(Low)*  ·  [DDL ↗](#103-loyalty-redemption)
`reward_id` PK/FK → `reward`; `inventory_total` / `inventory_remaining` with `CHECK(0 ≤ remaining ≤ total)`. Atomic decrement on reserve / restore on release — no oversell.

### 5.4 `reward_eligibility` — gates *(Low)*  ·  [DDL ↗](#103-loyalty-redemption)
`reward_id` PK/FK → `reward`; jsonb gates `tier_gate / segment_gate / tenure_gate / currency_gate / validity_window`; `per_member_cap`. Read-only on the hot path.

### 5.5 `redemption_saga` — orchestrator state *(Medium)*  ·  [DDL ↗](#103-loyalty-redemption)
| Column | Type | Null | Notes |
|---|---|---|---|
| `saga_id` | bigint identity | PK | |
| `member_id`, `program_id` | bigint | not null | **logical** → `loyalty-core` |
| `reward_id` | bigint | not null | FK → `reward` |
| `reservation_id` | bigint | null | **logical** → `loyalty-core.point_reservation` |
| `status` | varchar(20) | not null | `PENDING_RESERVATION \| RESERVED \| FULFILLING \| COMMITTED \| REJECTED \| RELEASED` |
| `external_ref`, `job_handle` | varchar(160) | null | partner ref / async-adapter handle |
| `idempotency_key` | varchar(160) | UNIQUE | mandatory on `POST /redeem` |

Single-writer = Orchestrator. Partial index over in-flight statuses for resume.

### 5.6 `redemption_audit_log` / `outbox` / `shedlock`  ·  [DDL ↗](#103-loyalty-redemption)
Standard. `outbox` carries `loyalty.redemption.*`. S3 bucket `loyalty-vouchers-*` holds Bill-Payment voucher PDFs (object storage, not a table). `shedlock` serialises outbox janitor + audit-archive flush (see [R2](#appendix-reconciliation-notes)).

---

## 6. `loyalty-campaign` RDS  →  [full DDL ↓](#104-loyalty-campaign)

7 tables.

### 6.1 `campaign` *(Low)*  ·  [DDL ↗](#104-loyalty-campaign)
`campaign_id` PK; `program_id` logical; window `CHECK(ends_at > starts_at)`; `target_segment` jsonb; `status DRAFT|SCHEDULED|LIVE|ENDED|ARCHIVED` (reconciled set, see [R3](#appendix-reconciliation-notes)).

### 6.2 `drawing` *(Low)*  ·  [DDL ↗](#104-loyalty-campaign)
`drawing_id` PK, FK → `campaign`; `scheduled_at`, `selection_strategy` (default `SEEDED_RNG`), `prize` jsonb, entry window, `allow_multiple_entries`; `status OPEN|CLOSED|COMPLETED|VOID` (reconciled, [R3](#appendix-reconciliation-notes)). Scheduler index on `scheduled_at WHERE status='OPEN'`.

### 6.3 `drawing_entry` *(Low)*  ·  [DDL ↗](#104-loyalty-campaign)
`entry_id` PK, FK → `drawing`; `member_id` logical; `redemption_id` **logical** → `loyalty-redemption`; `is_winner`; `UNIQUE(idempotency_key)`; index `(drawing_id, member_id)`. `(drawing_id, member_id)` is non-unique only when the Drawing allows multiple entries.

### 6.4 `winner_record` — immutable, audit-replayable *(Low)*  ·  [DDL ↗](#104-loyalty-campaign)
`winner_id` PK, FK → `drawing`; `seed_hex`, `winner_index` (`UNIQUE(drawing_id, winner_index)`), `drawn_at`. Given the row + the HMAC secret, the selection is deterministically re-verifiable (fairness audit).

### 6.5 `campaign_audit_log` / `outbox` / `shedlock`  ·  [DDL ↗](#104-loyalty-campaign)
Standard. `outbox` carries `loyalty.campaign.*`. `shedlock` serialises the Drawing Scheduler + outbox janitor.

---

## 7. `loyalty-integration-bridge` — no RDS

The Bridge is the Anti-Corruption Layer for inbound integrations and is **deliberately stateless except for Kafka consumer offsets** ([level-3-loyalty-integration-bridge](../c4/level-3-loyalty-integration-bridge.md)). It is consume → translate → produce with an idempotent producer and downstream `eventId` dedup, so there is **no outbox** and **no database** to catalogue. No DDL file exists for it.

---

## 8. Cross-service logical reference map

These columns carry another service's key **by value** with no SQL FK. Integrity is maintained by event/contract, not the database.

| From (RDS.table.column) | → To (RDS.table) | Set when |
|---|---|---|
| `core.point_ledger.rule_id` | `earning.earning_rule` | Earned entries |
| `core.point_reservation.reward_id` | `redemption.reward` | reservation created |
| `earning.earning_rule.program_id` | `core.program` | rule authored |
| `earning.earning_rule.campaign_id` | `campaign.campaign` | campaign-linked rule |
| `redemption.reward.program_id` | `core.program` | reward authored |
| `redemption.redemption_saga.member_id / program_id` | `core.member` / `core.program` | redemption start |
| `redemption.redemption_saga.reservation_id` | `core.point_reservation` | after reserve() |
| `campaign.campaign.program_id` | `core.program` | campaign authored |
| `campaign.drawing_entry.member_id` | `core.member` | entry created |
| `campaign.drawing_entry.redemption_id` | `redemption.redemption_saga` | sweepstakes entry via redemption |

`member.customer_id → Customer Service` is the one reference that leaves the platform entirely (PII boundary, [§5.3](../enterprise-architect.md#53-pii--data-protection)).

---

## 9. Validation

Every migration in [§10 (embedded DDL)](#10-embedded-ddl-full-sql) was applied to a throwaway **PostgreSQL 16** instance (one database per RDS) with `psql -v ON_ERROR_STOP=1` — all four apply cleanly. Invariants exercised: append succeeds on `point_ledger`; `UPDATE`/`DELETE` are rejected by `trg_point_ledger_immutable`; an `Adjusted` entry references its `approval_request` and the `fk_ledger_approval` FK rejects an unknown reference; a ledger entry for a non-existent `member_program` is rejected by FK. (The former `ck_ledger_adjusted_4eyes` 4-eyes CHECK was removed — 4-eyes now lives in BEP.)

---

## 10. Embedded DDL (full SQL)

The authoritative Flyway migrations, inlined here (PostgreSQL 16). Each block is the complete migration for that service; `loyalty-integration-bridge` has no database. The per-table sub-sections in §§3–6 link down to the relevant service block.

### 10.1 loyalty-core

```sql
-- =====================================================================
-- loyalty-core  —  V1__init.sql   (PostgreSQL 16, Flyway)
-- Bounded contexts: Membership + Ledger.
-- Owns the Program/Tier config, the Member identity + per-Program state,
-- the append-only Point Ledger (source of truth), the FIFO
-- cohort projection, point reservations, the
-- BEP-delegated approval-request queue and the multi-Program
-- governance scaffolding.
--
-- Conventions (see §2):
--   * Surrogate PKs: BIGINT GENERATED ALWAYS AS IDENTITY. member_id /
--     program_id are int64 to match the published API contract.
--   * Enumerations: VARCHAR + named CHECK constraint (evolvable without
--     ALTER TYPE; the platform is config-driven and these sets grow).
--   * Money/points: signed BIGINT (whole points; negative deltas allowed).
--   * Timestamps: TIMESTAMPTZ, default now() on create columns.
--   * Foreign keys are declared ONLY within this RDS. Cross-service
--     references (rule_id, reward_id, ...) are logical and carry no FK
--     because each service owns its own RDS.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Program (config) — system-of-record for Program + Tier lives in core.
-- Lifecycle is migration-driven, not BEP-driven.
-- ---------------------------------------------------------------------
CREATE TABLE program (
    program_id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_code            VARCHAR(32)  NOT NULL,
    name                    VARCHAR(160) NOT NULL,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    qualifying_metric       VARCHAR(20)  NOT NULL DEFAULT 'ROLLING_12_MONTHS',
    default_expiry_months   INT          NOT NULL DEFAULT 24,
    current_tcs_version     INT          NOT NULL DEFAULT 1,
    enrollment_model        VARCHAR(28)  NOT NULL DEFAULT 'EXPLICIT_OPT_IN',
    -- used only when enrollment_model = AUTO_ENROLL_ON_ELIGIBILITY
    eligibility_predicate_json JSONB,
    -- per-Earn-Source aggregate caps
    source_aggregate_caps   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_program_code UNIQUE (program_code),
    CONSTRAINT ck_program_status
        CHECK (status IN ('DRAFT','ACTIVE','SUNSET','CLOSED')),
    CONSTRAINT ck_program_qualifying_metric
        CHECK (qualifying_metric IN ('LIFETIME','ROLLING_12_MONTHS','CALENDAR_YEAR')),
    CONSTRAINT ck_program_enrollment_model
        CHECK (enrollment_model IN
            ('EXPLICIT_OPT_IN','AUTO_ENROLL_ON_ELIGIBILITY','AUTO_ENROLL_ALL','INVITE_ONLY')),
    CONSTRAINT ck_program_expiry_months CHECK (default_expiry_months > 0)
);
COMMENT ON TABLE  program IS 'Loyalty Program config. System-of-record in loyalty-core. Lifecycle (create/activate/sunset/close) is migration-driven.';
COMMENT ON COLUMN program.eligibility_predicate_json IS 'Predicate for AUTO_ENROLL_ON_ELIGIBILITY only; NULL otherwise.';

-- ---------------------------------------------------------------------
-- Tier ladder + tiers (config). One ladder per Program.
-- ---------------------------------------------------------------------
CREATE TABLE tier_ladder (
    ladder_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_id   BIGINT       NOT NULL,
    name         VARCHAR(120) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_ladder_program FOREIGN KEY (program_id) REFERENCES program (program_id),
    CONSTRAINT uq_ladder_program UNIQUE (program_id)
);

CREATE TABLE tier (
    tier_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ladder_id    BIGINT       NOT NULL,
    name         VARCHAR(80)  NOT NULL,
    ordinal      INT          NOT NULL,             -- 0 = entry tier
    threshold    BIGINT       NOT NULL,             -- qualifying points to enter
    -- multiplier, exclusive-reward flags, expiry override (months / never), downgrade grace knobs
    benefits     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_tier_ladder FOREIGN KEY (ladder_id) REFERENCES tier_ladder (ladder_id),
    CONSTRAINT uq_tier_ordinal UNIQUE (ladder_id, ordinal),
    CONSTRAINT ck_tier_threshold CHECK (threshold >= 0)
);
COMMENT ON COLUMN tier.benefits IS 'JSONB: {multiplier, exclusive_reward_ids[], expiry_override_months | never_expire, downgrade_policy: IMMEDIATE|GRACE_WINDOW, grace_window_days, grace_benefits: RETAIN|FREEZE} — expiry override; downgrade grace knobs (Program-default/Tier-override cascade; ordinal 0 never downgrades).';

-- ---------------------------------------------------------------------
-- Member — thin per-Customer identity (split). PII-free.
-- ---------------------------------------------------------------------
CREATE TABLE member (
    member_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id  BIGINT       NOT NULL,             -- logical FK -> RDB Customer Service
    preferences  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_member_customer UNIQUE (customer_id)
);
COMMENT ON TABLE member IS 'Per-Customer loyalty identity. PII-free: holds customerId only, never name/phone/NRC (CONTEXT.md PII Boundary, §5.3). Per-Program state lives in member_program.';

-- ---------------------------------------------------------------------
-- Member <-> Program junction. Carries per-Program state.
-- Balances here are a projection of the ledger (source of truth = point_ledger).
-- ---------------------------------------------------------------------
CREATE TABLE member_program (
    member_id            BIGINT      NOT NULL,
    program_id           BIGINT      NOT NULL,
    status               VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    redeemable_balance   BIGINT      NOT NULL DEFAULT 0,   -- signed: may go < 0
    qualifying_balance   BIGINT      NOT NULL DEFAULT 0,
    current_tier_id      BIGINT,
    tcs_version_accepted INT,                              -- NULL => earning gated
    eligibility_status   VARCHAR(24) NOT NULL DEFAULT 'ELIGIBLE',
    opt_in_at            TIMESTAMPTZ,
    opt_out_at           TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_member_program PRIMARY KEY (member_id, program_id),
    CONSTRAINT fk_mp_member  FOREIGN KEY (member_id)  REFERENCES member (member_id),
    CONSTRAINT fk_mp_program FOREIGN KEY (program_id) REFERENCES program (program_id),
    CONSTRAINT fk_mp_tier    FOREIGN KEY (current_tier_id) REFERENCES tier (tier_id),
    CONSTRAINT ck_mp_status
        CHECK (status IN ('ACTIVE','SUSPENDED_TCS','OPTED_OUT','CLOSED'))
);
COMMENT ON TABLE member_program IS 'Per-(Member,Program) enrollment state. No is_primary column — "primary" is a presentation-only construct.';
COMMENT ON COLUMN member_program.redeemable_balance IS 'Projection of SUM(redeemable_delta). Negative permitted after clawback; redemption blocked while < 0.';
COMMENT ON COLUMN member_program.tcs_version_accepted IS 'NULL = T&Cs not yet accepted; Earn Service silently drops earn for NULL members.';

CREATE INDEX ix_mp_program_tier ON member_program (program_id, current_tier_id);

-- ---------------------------------------------------------------------
-- Approval request — generic record of a proposed change awaiting BEP
-- approval. Loyalty owns the proposed change; the bank's
-- existing BEP Approval Workflow owns routing, Job Roles, 4-eyes and caps.
-- Serves manual adjustments AND config activation. Defined before
-- point_ledger so an Adjusted entry can FK back to its approval.
-- ---------------------------------------------------------------------
CREATE TABLE approval_request (
    request_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_type     VARCHAR(40)  NOT NULL,   -- ADJUSTMENT | RULE_ACTIVATION | REWARD_CHANGE | TIER_CHANGE | TCS_VERSION | ...
    payload          JSONB        NOT NULL,    -- the proposed change (e.g. {memberId, programId, qualifyingDelta, redeemableDelta, reason, caseReference})
    status           VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    requested_by     VARCHAR(64),              -- BEP actor that raised it (informational)
    bep_approval_ref VARCHAR(120),             -- reference to the BEP approval-workflow instance; BEP is system-of-record for who approved
    applied_ref      BIGINT,                   -- logical ref to the resulting artifact (e.g. point_ledger.entry_id); no FK (avoids a cycle)
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    applied_at       TIMESTAMPTZ,
    CONSTRAINT ck_approval_status CHECK (status IN ('PENDING','APPLIED','REJECTED'))
);
COMMENT ON TABLE approval_request IS 'Proposed change pending BEP approval. Loyalty stores only bep_approval_ref; it does not enforce 4-eyes or caps — BEP''s Approval Workflow does. No maker/checker columns by design.';
CREATE INDEX ix_approval_pending ON approval_request (request_type, created_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------
-- Point Ledger — append-only, immutable. Source of truth.
-- ---------------------------------------------------------------------
CREATE TABLE point_ledger (
    entry_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id        BIGINT      NOT NULL,
    program_id       BIGINT      NOT NULL,
    qualifying_delta BIGINT      NOT NULL,
    redeemable_delta BIGINT      NOT NULL,
    entry_type       VARCHAR(16) NOT NULL,
    source_ref       VARCHAR(160) NOT NULL,          -- idempotency key (with entry_type)
    rule_id          BIGINT,                          -- logical -> loyalty-earning.earning_rule (Earned only)
    expires_at       TIMESTAMPTZ,                     -- set on Earned
    approval_request_id BIGINT,                       -- set on Adjusted; the BEP-approved request
    reason           TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ledger_member_program
        FOREIGN KEY (member_id, program_id) REFERENCES member_program (member_id, program_id),
    CONSTRAINT fk_ledger_approval FOREIGN KEY (approval_request_id) REFERENCES approval_request (request_id),
    CONSTRAINT uq_ledger_source UNIQUE (source_ref, entry_type),
    CONSTRAINT ck_ledger_entry_type CHECK (entry_type IN
        ('Earned','Redeemed','Expired','Reversed','Adjusted','Converted-Out','Converted-In'))
);
COMMENT ON TABLE point_ledger IS 'Append-only Point movements. No UPDATE/DELETE ever; enforced by trg_point_ledger_immutable. balance = SUM(deltas) per (member,program).';

CREATE INDEX ix_ledger_member_program ON point_ledger (member_id, program_id, created_at);
CREATE INDEX ix_ledger_rule ON point_ledger (rule_id) WHERE rule_id IS NOT NULL;

-- Enforce ledger immutability at the DB tier (defence-in-depth).
CREATE OR REPLACE FUNCTION fn_point_ledger_immutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'point_ledger is append-only: % rejected', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_point_ledger_immutable
    BEFORE UPDATE OR DELETE ON point_ledger
    FOR EACH ROW EXECUTE FUNCTION fn_point_ledger_immutable();

-- ---------------------------------------------------------------------
-- Point cohort — per-Earned-entry FIFO consumption tracker.
-- Rebuildable projection; loss does not destroy the ledger.
-- ---------------------------------------------------------------------
CREATE TABLE point_cohort (
    cohort_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_entry_id  BIGINT      NOT NULL,            -- the Earned entry
    member_id        BIGINT      NOT NULL,
    program_id       BIGINT      NOT NULL,
    original_amount  BIGINT      NOT NULL,
    consumed_amount  BIGINT      NOT NULL DEFAULT 0,
    expired_amount   BIGINT      NOT NULL DEFAULT 0,
    expires_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_cohort_ledger FOREIGN KEY (ledger_entry_id) REFERENCES point_ledger (entry_id),
    CONSTRAINT uq_cohort_ledger UNIQUE (ledger_entry_id),
    CONSTRAINT ck_cohort_amounts
        CHECK (original_amount >= 0 AND consumed_amount >= 0 AND expired_amount >= 0
               AND consumed_amount + expired_amount <= original_amount)
);
-- FIFO consumption scan: oldest unconsumed cohort first.
CREATE INDEX ix_cohort_fifo ON point_cohort (member_id, program_id, expires_at)
    WHERE consumed_amount + expired_amount < original_amount;
-- Expiry-job sweep.
CREATE INDEX ix_cohort_expiry ON point_cohort (expires_at)
    WHERE consumed_amount + expired_amount < original_amount;

-- ---------------------------------------------------------------------
-- Point reservation — transient two-phase redemption hold.
-- NOT append-only; lives outside the ledger.
-- ---------------------------------------------------------------------
CREATE TABLE point_reservation (
    reservation_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id       BIGINT      NOT NULL,
    program_id      BIGINT      NOT NULL,
    reward_id       BIGINT      NOT NULL,             -- logical -> loyalty-redemption.reward
    reserved_points BIGINT      NOT NULL,
    status          VARCHAR(12) NOT NULL DEFAULT 'HELD',
    held_until      TIMESTAMPTZ NOT NULL,             -- TTL; drives the sweeper
    idempotency_key VARCHAR(160) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_resv_member_program
        FOREIGN KEY (member_id, program_id) REFERENCES member_program (member_id, program_id),
    CONSTRAINT uq_resv_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_resv_status CHECK (status IN ('HELD','COMMITTED','RELEASED')),
    CONSTRAINT ck_resv_points CHECK (reserved_points > 0)
);
-- TTL sweeper: find HELD reservations past held_until.
CREATE INDEX ix_resv_sweep ON point_reservation (held_until) WHERE status = 'HELD';

-- (Manual adjustments are recorded as approval_request rows of type
-- ADJUSTMENT, defined above; on BEP approval an 'Adjusted' point_ledger
-- entry is written referencing approval_request_id.)

-- ---------------------------------------------------------------------
-- Program conversion rule — EMPTY in v1; schema reserved.
-- ---------------------------------------------------------------------
CREATE TABLE program_conversion_rule (
    src_program_id      BIGINT      NOT NULL,
    dst_program_id      BIGINT      NOT NULL,
    direction           VARCHAR(13) NOT NULL,
    rate_numerator      BIGINT      NOT NULL,
    rate_denominator    BIGINT      NOT NULL,
    daily_cap_per_member BIGINT,
    effective_from      TIMESTAMPTZ,
    effective_to        TIMESTAMPTZ,
    status              VARCHAR(12) NOT NULL DEFAULT 'DRAFT',
    bep_approval_ref    VARCHAR(120),                    -- BEP approval-workflow reference
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_conversion PRIMARY KEY (src_program_id, dst_program_id),
    CONSTRAINT fk_conv_src FOREIGN KEY (src_program_id) REFERENCES program (program_id),
    CONSTRAINT fk_conv_dst FOREIGN KEY (dst_program_id) REFERENCES program (program_id),
    CONSTRAINT ck_conv_direction CHECK (direction IN ('ONE_WAY','BIDIRECTIONAL')),
    CONSTRAINT ck_conv_rate CHECK (rate_numerator > 0 AND rate_denominator > 0),
    CONSTRAINT ck_conv_no_self CHECK (src_program_id <> dst_program_id)
);
COMMENT ON TABLE program_conversion_rule IS 'Reserved empty in v1. Populated only when a second Program defines a conversion policy; Program-2 activation is gated on this decision.';

-- ---------------------------------------------------------------------
-- Per-service audit log. Tamper-evident: hash-chained + DB-tier
-- immutable in Postgres (hot copy), then the nightly audit-archive-flush
-- ShedLock job seals chained NDJSON segments to S3 Object Lock
-- (GOVERNANCE mode, >=7yr WORM). Resolves the prior "cold-archived to S3" note.
-- ---------------------------------------------------------------------
CREATE TABLE core_audit_log (
    log_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_keycloak_id VARCHAR(64) NOT NULL,
    action           VARCHAR(80) NOT NULL,
    entity_type      VARCHAR(80) NOT NULL,
    entity_id        VARCHAR(80) NOT NULL,
    before_json      JSONB,
    after_json       JSONB,
    occurred_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    prev_hash        CHAR(64),                        -- row_hash of the prior entry; NULL only on the genesis row
    row_hash         CHAR(64) NOT NULL                -- SHA-256(canonical(log_id..occurred_at) || COALESCE(prev_hash,'')); set in the append txn, serialised per table
);
COMMENT ON TABLE core_audit_log IS 'Append-only, hash-chained (prev_hash/row_hash), DB-tier immutable; nightly-sealed to S3 Object Lock WORM. No UPDATE/DELETE ever.';
CREATE INDEX ix_core_audit_entity ON core_audit_log (entity_type, entity_id);
CREATE INDEX ix_core_audit_time   ON core_audit_log (occurred_at);

-- Tamper-evidence: reject mutation at the DB tier (parity with point_ledger).
CREATE OR REPLACE FUNCTION fn_core_audit_immutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'core_audit_log is append-only: % rejected', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_core_audit_immutable
    BEFORE UPDATE OR DELETE ON core_audit_log
    FOR EACH ROW EXECUTE FUNCTION fn_core_audit_immutable();

-- ---------------------------------------------------------------------
-- Transactional outbox — loyalty.member.* and loyalty.ledger.* events.
-- ---------------------------------------------------------------------
CREATE TABLE outbox (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(48)  NOT NULL,
    aggregate_id   VARCHAR(80)  NOT NULL,
    event_type     VARCHAR(80)  NOT NULL,
    topic          VARCHAR(120) NOT NULL,
    payload        JSONB        NOT NULL,
    headers        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status         VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','SENT'))
);
-- Outbox Relay drains pending rows oldest-first.
CREATE INDEX ix_outbox_pending ON outbox (created_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------
-- ShedLock — distributed lock for @Scheduled jobs.
-- Library-owned schema; one row per scheduled method.
-- Jobs in loyalty-core: Expiry, Reservation TTL Sweeper, tier-downgrade
-- re-evaluation, outbox janitor, audit-archive WORM seal +
-- audit-chain verifier.
-- ---------------------------------------------------------------------
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

### 10.2 loyalty-earning

`V1__init.sql`:

```sql
-- =====================================================================
-- loyalty-earning  —  V1__init.sql   (PostgreSQL 16, Flyway)
-- Bounded context: Earning. Event-driven Kafka consumer that
-- evaluates per-Program Earning Rules (constrained JSON DSL),
-- enforces caps, dedups by eventId, and emits PointsEarned via outbox.
--
-- Conventions: see the loyalty-core DDL header above and §2. program_id / member_id / campaign_id are
-- logical references (no cross-RDS FK).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Earn Source registry — catalogue of accepted earn-source codes.
-- A new Earn Source = a new translation in loyalty-integration-bridge.
-- ---------------------------------------------------------------------
CREATE TABLE earn_source (
    earn_source_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    earn_source_code   VARCHAR(48)  NOT NULL,           -- e.g. CARD_SPEND, BALANCE_THRESHOLD
    display_name       VARCHAR(120) NOT NULL,
    domain_event_types JSONB        NOT NULL DEFAULT '[]'::jsonb,  -- list of canonical event types
    active_by_default  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_earn_source_code UNIQUE (earn_source_code)
);

-- ---------------------------------------------------------------------
-- Earning Rule — per-Program rule in the constrained JSON DSL.
-- Versioned; never deleted (audit). DRAFT -> ACTIVE -> ARCHIVED.
-- ---------------------------------------------------------------------
CREATE TABLE earning_rule (
    rule_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_id     BIGINT       NOT NULL,              -- logical -> loyalty-core.program
    earn_source_id BIGINT       NOT NULL,
    dsl_json       JSONB        NOT NULL,
    version        INT          NOT NULL DEFAULT 1,
    status         VARCHAR(10)  NOT NULL DEFAULT 'DRAFT',
    effective_from TIMESTAMPTZ,
    effective_to   TIMESTAMPTZ,
    campaign_id    BIGINT,                              -- logical -> loyalty-campaign.campaign (nullable)
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_rule_earn_source FOREIGN KEY (earn_source_id) REFERENCES earn_source (earn_source_id),
    CONSTRAINT ck_rule_status CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
    CONSTRAINT ck_rule_window CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from)
);
COMMENT ON COLUMN earning_rule.dsl_json IS 'Canonical decision-table DSL JSON. Schema-validated at save (earning-rule.schema.json, normative); no Drools/Groovy runtime.';
-- Hot path: active rules for a (program, source) at evaluation time.
CREATE INDEX ix_rule_lookup ON earning_rule (program_id, earn_source_id, status);
CREATE INDEX ix_rule_campaign ON earning_rule (campaign_id) WHERE campaign_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- Cap counters — per (program, rule, member, window) cap accumulation.
-- Nightly job purges expired windows.
-- ---------------------------------------------------------------------
CREATE TABLE cap_counter (
    cap_counter_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_id     BIGINT       NOT NULL,
    rule_id        BIGINT       NOT NULL,
    member_id      BIGINT       NOT NULL,
    window_key     VARCHAR(40)  NOT NULL,              -- e.g. '2026-05-27' or rolling-window id
    counter_value  BIGINT       NOT NULL DEFAULT 0,
    window_start   TIMESTAMPTZ  NOT NULL,
    window_end     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_cap_rule FOREIGN KEY (rule_id) REFERENCES earning_rule (rule_id),
    CONSTRAINT uq_cap_window UNIQUE (program_id, rule_id, member_id, window_key),
    CONSTRAINT ck_cap_value CHECK (counter_value >= 0)
);
-- Purge job sweeps windows whose end has passed.
CREATE INDEX ix_cap_purge ON cap_counter (window_end);

-- ---------------------------------------------------------------------
-- Idempotency keys — one row per processed eventId. Cheap short-circuit
-- before any rule work. TTL-purged after 90d (Kafka retention + grace).
-- ---------------------------------------------------------------------
CREATE TABLE idempotency_key (
    event_id     VARCHAR(160) NOT NULL PRIMARY KEY,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_idem_purge ON idempotency_key (processed_at);

-- ---------------------------------------------------------------------
-- Per-service audit log. Tamper-evident: hash-chained + DB-tier
-- immutable in Postgres (hot copy); nightly audit-archive-flush ShedLock job
-- seals chained NDJSON segments to S3 Object Lock (GOVERNANCE, >=7yr WORM).
-- ---------------------------------------------------------------------
CREATE TABLE earning_audit_log (
    log_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_keycloak_id VARCHAR(64) NOT NULL,
    action            VARCHAR(80) NOT NULL,
    entity_type       VARCHAR(80) NOT NULL,
    entity_id         VARCHAR(80) NOT NULL,
    before_json       JSONB,
    after_json        JSONB,
    occurred_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    prev_hash         CHAR(64),                       -- row_hash of the prior entry; NULL only on the genesis row
    row_hash          CHAR(64) NOT NULL               -- SHA-256(canonical(log_id..occurred_at) || COALESCE(prev_hash,'')); set in the append txn, serialised per table
);
COMMENT ON TABLE earning_audit_log IS 'Append-only, hash-chained (prev_hash/row_hash), DB-tier immutable; nightly-sealed to S3 Object Lock WORM. No UPDATE/DELETE ever.';
CREATE INDEX ix_earning_audit_entity ON earning_audit_log (entity_type, entity_id);
CREATE INDEX ix_earning_audit_time   ON earning_audit_log (occurred_at);

-- Tamper-evidence: reject mutation at the DB tier (parity with point_ledger).
CREATE OR REPLACE FUNCTION fn_earning_audit_immutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'earning_audit_log is append-only: % rejected', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_earning_audit_immutable
    BEFORE UPDATE OR DELETE ON earning_audit_log
    FOR EACH ROW EXECUTE FUNCTION fn_earning_audit_immutable();

-- ---------------------------------------------------------------------
-- Transactional outbox — loyalty.earning.PointsEarned.
-- ---------------------------------------------------------------------
CREATE TABLE outbox (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(48)  NOT NULL,
    aggregate_id   VARCHAR(80)  NOT NULL,
    event_type     VARCHAR(80)  NOT NULL,
    topic          VARCHAR(120) NOT NULL,
    payload        JSONB        NOT NULL,
    headers        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status         VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','SENT'))
);
CREATE INDEX ix_outbox_pending ON outbox (created_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------
-- ShedLock. Jobs in loyalty-earning: cap-counter purge,
-- idempotency-key purge, outbox janitor, audit-archive WORM seal +
-- audit-chain verifier. (Not shown in the L3 owned-
-- tables list; required because these jobs run multi-Pod — see the
-- Appendix reconciliation note R2 below.)
-- ---------------------------------------------------------------------
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

`V2__seed_v1_earn_sources.sql` — seeds the v1 Earn Source catalogue:

```sql
-- =====================================================================
-- loyalty-earning  —  V2__seed_v1_earn_sources.sql   (PostgreSQL 16, Flyway)
-- Seeds the v1 Earn Source catalogue.
--
-- Five active sources, all in the **pure event-driven** evaluation pattern
-- (one upstream domain event → Rule Engine → 1..N ledger entries, ≤30s p99).
-- Plus one inactive fallback (PAYMENT_COMPLETED) absorbing unmapped
-- producer `paymentType` values per the EMIT_FALLBACK_AND_ALERT policy.
--
-- Deferred (NOT seeded here):
--   Engagement:    OPT_IN_BONUS, PROFILE_COMPLETED, TUTORIAL_COMPLETED,
--                  EKYC_COMPLETED, DAILY_CHECKIN
--   State-derived: BALANCE_THRESHOLD, MIN_BALANCE_HELD
--   Two-phase:     REFERRAL_COMPLETED
-- =====================================================================

INSERT INTO earn_source (earn_source_code, display_name, domain_event_types, active_by_default) VALUES
  ('CARD_SPEND',
     'Card Spend (post-settle)',
     '["paymenthub.card_spend.v1"]'::jsonb,
     TRUE),
  ('BILL_PAYMENT',
     'Bill Payment',
     '["paymenthub.payment_completed.v1"]'::jsonb,
     TRUE),
  ('FUND_TRANSFER',
     'Fund Transfer (includes P2P and QR — DSL discriminates via canonical paymentType)',
     '["paymenthub.payment_completed.v1"]'::jsonb,
     TRUE),
  ('TOPUP',
     'Mobile / E-wallet Top-up',
     '["paymenthub.payment_completed.v1"]'::jsonb,
     TRUE),
  ('TERM_DEPOSIT_OPENED',
     'Term Deposit Opened',
     '["t24adapter.account_state.v1"]'::jsonb,
     TRUE),
  ('PAYMENT_COMPLETED',
     'Fallback — unmapped producer paymentType (EMIT_FALLBACK_AND_ALERT)',
     '["paymenthub.payment_completed.v1"]'::jsonb,
     FALSE)
ON CONFLICT (earn_source_code) DO NOTHING;

COMMENT ON TABLE earn_source IS
  'Earn Source registry. v1 catalogue (5 active + 1 inactive fallback). New sources require an event subscription in loyalty-integration-bridge, and state-derived/engagement sources need additional design before activation.';
```

### 10.3 loyalty-redemption

```sql
-- =====================================================================
-- loyalty-redemption  —  V1__init.sql   (PostgreSQL 16, Flyway)
-- Bounded contexts: Reward + Fulfillment. Owns the Reward
-- catalogue, eligibility gates, optional inventory, and the redemption
-- Saga state (two-phase redemption). Reservations + the Point
-- Ledger live in loyalty-core; reservation_id / member_id here are logical.
--
-- Conventions: see the loyalty-core DDL header above and §2.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Reward Type — platform config; binds a reward kind to a fulfillment
-- adapter class and a parameter schema. Seeded, not BEP-authored.
-- ---------------------------------------------------------------------
CREATE TABLE reward_type (
    reward_type_code        VARCHAR(48)  NOT NULL PRIMARY KEY,  -- e.g. CASHBACK, BILL_VOUCHER, THIRD_PARTY_VOUCHER, SWEEPSTAKES
    display_name            VARCHAR(120) NOT NULL,
    fulfillment_adapter_class VARCHAR(200) NOT NULL,
    parameter_schema        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Reward — per-Program catalogue entry. Immutable once redeemed against
-- (versioned via reward_revision).
-- ---------------------------------------------------------------------
CREATE TABLE reward (
    reward_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_id         BIGINT       NOT NULL,           -- logical -> loyalty-core.program
    reward_type_code   VARCHAR(48)  NOT NULL,
    reward_revision    INT          NOT NULL DEFAULT 1,
    name               VARCHAR(160) NOT NULL,
    point_cost         BIGINT       NOT NULL,
    fulfillment_params JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status             VARCHAR(10)  NOT NULL DEFAULT 'DRAFT',
    validity_from      TIMESTAMPTZ,
    validity_to        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_reward_type FOREIGN KEY (reward_type_code) REFERENCES reward_type (reward_type_code),
    CONSTRAINT ck_reward_status CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
    CONSTRAINT ck_reward_cost CHECK (point_cost >= 0),
    CONSTRAINT ck_reward_validity CHECK (validity_to IS NULL OR validity_from IS NULL OR validity_to > validity_from)
);
CREATE INDEX ix_reward_program_status ON reward (program_id, status);

-- ---------------------------------------------------------------------
-- Reward inventory — optional per-Reward stock cap. Atomic decrement on
-- reserve, restore on release (no oversell).
-- ---------------------------------------------------------------------
CREATE TABLE reward_inventory (
    reward_id           BIGINT      NOT NULL PRIMARY KEY,
    inventory_total     BIGINT      NOT NULL,
    inventory_remaining BIGINT      NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_inv_reward FOREIGN KEY (reward_id) REFERENCES reward (reward_id),
    CONSTRAINT ck_inv_amounts CHECK (inventory_total >= 0
        AND inventory_remaining >= 0
        AND inventory_remaining <= inventory_total)
);

-- ---------------------------------------------------------------------
-- Reward eligibility — gates evaluated read-only on the hot path.
-- One row per Reward (authored in BEP).
-- ---------------------------------------------------------------------
CREATE TABLE reward_eligibility (
    reward_id      BIGINT NOT NULL PRIMARY KEY,
    tier_gate      JSONB,                               -- allowed tier ids / min tier ordinal
    segment_gate   JSONB,
    tenure_gate    JSONB,                               -- min membership tenure
    currency_gate  JSONB,
    per_member_cap INT,                                 -- max redemptions per member
    validity_window JSONB,
    CONSTRAINT fk_elig_reward FOREIGN KEY (reward_id) REFERENCES reward (reward_id),
    CONSTRAINT ck_elig_cap CHECK (per_member_cap IS NULL OR per_member_cap > 0)
);

-- ---------------------------------------------------------------------
-- Redemption Saga — orchestrator state machine. Single-writer
-- = Redemption Orchestrator. reservation_id is logical (lives in core).
-- ---------------------------------------------------------------------
CREATE TABLE redemption_saga (
    saga_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id       BIGINT       NOT NULL,             -- logical -> loyalty-core.member
    program_id      BIGINT       NOT NULL,             -- logical -> loyalty-core.program
    reward_id       BIGINT       NOT NULL,
    reservation_id  BIGINT,                             -- logical -> loyalty-core.point_reservation
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING_RESERVATION',
    external_ref    VARCHAR(160),                       -- partner/voucher reference
    job_handle      VARCHAR(160),                       -- async-adapter poll/callback handle
    idempotency_key VARCHAR(160) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_saga_reward FOREIGN KEY (reward_id) REFERENCES reward (reward_id),
    CONSTRAINT uq_saga_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_saga_status CHECK (status IN
        ('PENDING_RESERVATION','RESERVED','FULFILLING','COMMITTED','REJECTED','RELEASED'))
);
CREATE INDEX ix_saga_member ON redemption_saga (member_id, program_id);
-- In-flight sagas (resume after restart / async callback).
CREATE INDEX ix_saga_inflight ON redemption_saga (status)
    WHERE status IN ('PENDING_RESERVATION','RESERVED','FULFILLING');

-- ---------------------------------------------------------------------
-- Per-service audit log. Tamper-evident: hash-chained + DB-tier
-- immutable in Postgres (hot copy); nightly audit-archive-flush ShedLock job
-- seals chained NDJSON segments to S3 Object Lock (GOVERNANCE, >=7yr WORM).
-- ---------------------------------------------------------------------
CREATE TABLE redemption_audit_log (
    log_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_keycloak_id VARCHAR(64) NOT NULL,
    action            VARCHAR(80) NOT NULL,
    entity_type       VARCHAR(80) NOT NULL,
    entity_id         VARCHAR(80) NOT NULL,
    before_json       JSONB,
    after_json        JSONB,
    occurred_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    prev_hash         CHAR(64),                       -- row_hash of the prior entry; NULL only on the genesis row
    row_hash          CHAR(64) NOT NULL               -- SHA-256(canonical(log_id..occurred_at) || COALESCE(prev_hash,'')); set in the append txn, serialised per table
);
COMMENT ON TABLE redemption_audit_log IS 'Append-only, hash-chained (prev_hash/row_hash), DB-tier immutable; nightly-sealed to S3 Object Lock WORM. No UPDATE/DELETE ever.';
CREATE INDEX ix_redemption_audit_entity ON redemption_audit_log (entity_type, entity_id);
CREATE INDEX ix_redemption_audit_time   ON redemption_audit_log (occurred_at);

-- Tamper-evidence: reject mutation at the DB tier (parity with point_ledger).
CREATE OR REPLACE FUNCTION fn_redemption_audit_immutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'redemption_audit_log is append-only: % rejected', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_redemption_audit_immutable
    BEFORE UPDATE OR DELETE ON redemption_audit_log
    FOR EACH ROW EXECUTE FUNCTION fn_redemption_audit_immutable();

-- ---------------------------------------------------------------------
-- Transactional outbox — loyalty.redemption.*.
-- ---------------------------------------------------------------------
CREATE TABLE outbox (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(48)  NOT NULL,
    aggregate_id   VARCHAR(80)  NOT NULL,
    event_type     VARCHAR(80)  NOT NULL,
    topic          VARCHAR(120) NOT NULL,
    payload        JSONB        NOT NULL,
    headers        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status         VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','SENT'))
);
CREATE INDEX ix_outbox_pending ON outbox (created_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------
-- ShedLock. Jobs in loyalty-redemption: outbox janitor,
-- audit-archive WORM seal + audit-chain verifier. (Not in the L3 owned-tables list; see the
-- Appendix reconciliation note R2 below.)
-- ---------------------------------------------------------------------
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

### 10.4 loyalty-campaign

```sql
-- =====================================================================
-- loyalty-campaign  —  V1__init.sql   (PostgreSQL 16, Flyway)
-- Bounded context: Campaign. Owns Campaign definitions,
-- Drawings (sweepstakes), per-Member entries, and the immutable,
-- audit-replayable winner records (seeded-RNG selection).
--
-- Conventions: see the loyalty-core DDL header above and §2. program_id / member_id / redemption_id are
-- logical references (no cross-RDS FK).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Campaign — per-Program campaign definition (multiplier rule, window).
-- ---------------------------------------------------------------------
CREATE TABLE campaign (
    campaign_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_id     BIGINT       NOT NULL,              -- logical -> loyalty-core.program
    name           VARCHAR(160) NOT NULL,
    starts_at      TIMESTAMPTZ  NOT NULL,
    ends_at        TIMESTAMPTZ  NOT NULL,
    target_segment JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status         VARCHAR(12)  NOT NULL DEFAULT 'DRAFT',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_campaign_status CHECK (status IN ('DRAFT','SCHEDULED','LIVE','ENDED','ARCHIVED')),
    CONSTRAINT ck_campaign_window CHECK (ends_at > starts_at)
);
CREATE INDEX ix_campaign_program_status ON campaign (program_id, status);

-- ---------------------------------------------------------------------
-- Drawing — sweepstakes definition: prize, entry window, draw time.
-- ---------------------------------------------------------------------
CREATE TABLE drawing (
    drawing_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id        BIGINT       NOT NULL,
    scheduled_at       TIMESTAMPTZ  NOT NULL,           -- when the draw runs
    selection_strategy VARCHAR(40)  NOT NULL DEFAULT 'SEEDED_RNG',  -- SEEDED_RNG | WEIGHTED | FIRST_N
    winners_count      INT          NOT NULL DEFAULT 1,             -- K winners per draw
    prize              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    entry_window_start TIMESTAMPTZ  NOT NULL,
    entry_window_end   TIMESTAMPTZ  NOT NULL,
    allow_multiple_entries BOOLEAN  NOT NULL DEFAULT FALSE,
    status             VARCHAR(12)  NOT NULL DEFAULT 'OPEN',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_drawing_campaign FOREIGN KEY (campaign_id) REFERENCES campaign (campaign_id),
    CONSTRAINT ck_drawing_status CHECK (status IN ('OPEN','CLOSED','COMPLETED','VOID')),
    CONSTRAINT ck_drawing_window CHECK (entry_window_end > entry_window_start),
    CONSTRAINT ck_drawing_strategy CHECK (selection_strategy IN ('SEEDED_RNG','WEIGHTED','FIRST_N')),
    CONSTRAINT ck_drawing_winners CHECK (winners_count > 0)
);
-- Drawing Scheduler sweep: OPEN drawings whose scheduled_at has arrived.
CREATE INDEX ix_drawing_schedule ON drawing (scheduled_at) WHERE status = 'OPEN';
CREATE INDEX ix_drawing_campaign ON drawing (campaign_id, status);

-- ---------------------------------------------------------------------
-- Drawing entry — one row per Member entry. idempotency_key unique;
-- (drawing_id, member_id) non-unique only when allow_multiple_entries.
-- ---------------------------------------------------------------------
CREATE TABLE drawing_entry (
    entry_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    drawing_id      BIGINT       NOT NULL,
    member_id       BIGINT       NOT NULL,             -- logical -> loyalty-core.member
    redemption_id   BIGINT,                             -- logical -> loyalty-redemption.redemption_saga
    is_winner       BOOLEAN      NOT NULL DEFAULT FALSE,
    weight          INT          NOT NULL DEFAULT 1,    -- WEIGHTED odds; frozen at entry for replay
    idempotency_key VARCHAR(160) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_entry_drawing FOREIGN KEY (drawing_id) REFERENCES drawing (drawing_id),
    CONSTRAINT uq_entry_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_entry_weight CHECK (weight > 0)
);
CREATE INDEX ix_entry_drawing_member ON drawing_entry (drawing_id, member_id);

-- ---------------------------------------------------------------------
-- Winner record — immutable, audit-replayable. Given the row + the HMAC
-- secret, the winner selection can be re-verified deterministically.
-- ---------------------------------------------------------------------
CREATE TABLE winner_record (
    winner_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    drawing_id   BIGINT       NOT NULL,
    member_id    BIGINT       NOT NULL,
    seed_hex     VARCHAR(128),                         -- seed used (SEEDED_RNG/WEIGHTED); NULL for FIRST_N
    winner_index BIGINT       NOT NULL,                -- winner's index into the entry set ordered by entry_id (arrival rank for FIRST_N)
    drawn_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_winner_drawing FOREIGN KEY (drawing_id) REFERENCES drawing (drawing_id),
    CONSTRAINT uq_winner_per_index UNIQUE (drawing_id, winner_index)
);
COMMENT ON TABLE winner_record IS 'Immutable. K rows per drawing (winners_count). seed_hex + HMAC secret + frozen entry order/weights reproduce SEEDED_RNG/WEIGHTED selection; FIRST_N = first K by entry_id (seed_hex NULL). UNIQUE(drawing_id,winner_index) blocks duplicate selection. No UPDATE/DELETE by convention.';

-- ---------------------------------------------------------------------
-- Per-service audit log. Tamper-evident: hash-chained + DB-tier
-- immutable in Postgres (hot copy); nightly audit-archive-flush ShedLock job
-- seals chained NDJSON segments to S3 Object Lock (GOVERNANCE, >=7yr WORM).
-- ---------------------------------------------------------------------
CREATE TABLE campaign_audit_log (
    log_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_keycloak_id VARCHAR(64) NOT NULL,
    action            VARCHAR(80) NOT NULL,
    entity_type       VARCHAR(80) NOT NULL,
    entity_id         VARCHAR(80) NOT NULL,
    before_json       JSONB,
    after_json        JSONB,
    occurred_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    prev_hash         CHAR(64),                       -- row_hash of the prior entry; NULL only on the genesis row
    row_hash          CHAR(64) NOT NULL               -- SHA-256(canonical(log_id..occurred_at) || COALESCE(prev_hash,'')); set in the append txn, serialised per table
);
COMMENT ON TABLE campaign_audit_log IS 'Append-only, hash-chained (prev_hash/row_hash), DB-tier immutable; nightly-sealed to S3 Object Lock WORM. No UPDATE/DELETE ever.';
CREATE INDEX ix_campaign_audit_entity ON campaign_audit_log (entity_type, entity_id);
CREATE INDEX ix_campaign_audit_time   ON campaign_audit_log (occurred_at);

-- Tamper-evidence: reject mutation at the DB tier (parity with point_ledger).
CREATE OR REPLACE FUNCTION fn_campaign_audit_immutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'campaign_audit_log is append-only: % rejected', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_campaign_audit_immutable
    BEFORE UPDATE OR DELETE ON campaign_audit_log
    FOR EACH ROW EXECUTE FUNCTION fn_campaign_audit_immutable();

-- ---------------------------------------------------------------------
-- Transactional outbox — loyalty.campaign.*.
-- ---------------------------------------------------------------------
CREATE TABLE outbox (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(48)  NOT NULL,
    aggregate_id   VARCHAR(80)  NOT NULL,
    event_type     VARCHAR(80)  NOT NULL,
    topic          VARCHAR(120) NOT NULL,
    payload        JSONB        NOT NULL,
    headers        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status         VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','SENT'))
);
CREATE INDEX ix_outbox_pending ON outbox (created_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------
-- ShedLock. Jobs in loyalty-campaign: Drawing Scheduler,
-- outbox janitor, audit-archive WORM seal + audit-chain verifier.
-- ---------------------------------------------------------------------
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

---

## Appendix: reconciliation notes

Where this catalogue diverges from an upstream document, the newer/authoritative decision wins; the divergence is recorded here so a future reader isn't surprised.

- **R1 — Member is split (junction model).** The [conceptual model §5.2](../enterprise-architect.md#52-conceptual-data-model) and the `loyalty-core` L3 note place balances/tier/T&Cs directly on `member` (single-Program shortcut). This catalogue uses the **junction model**: a thin `member` (identity) + a `member_program` junction carrying per-Program `status / redeemable_balance / qualifying_balance / current_tier_id / tcs_version_accepted / eligibility_status`. This is what lets Program-2 launch in v1.x with **no live-ledger migration**.
- **R2 — `shedlock` present in all four backend RDS.** The L3 owned-tables lists show `shedlock` only for `loyalty-core` and `loyalty-campaign`. *Every service that owns a scheduled job* has a `shedlock` table; `loyalty-earning` runs cap-counter + idempotency-key purges and `loyalty-redemption` runs the outbox janitor + audit-archive flush, so both need it. Added accordingly.
- **R3 — Status-enum supersets.** `campaign.status` and `drawing.status` differ between §5.2 and the campaign L3 (`DRAFT/SCHEDULED/LIVE/ENDED` vs `DRAFT/ACTIVE/ARCHIVED`; `OPEN/COMPLETED` vs `OPEN/CLOSED/VOID`). This catalogue uses the **union** (`campaign: DRAFT|SCHEDULED|LIVE|ENDED|ARCHIVED`; `drawing: OPEN|CLOSED|COMPLETED|VOID`) so no documented transition is unrepresentable. Tighten in detailed design if a transition is proven impossible.
- **R4 — `program`/`tier` placed in `loyalty-core`.** §5.1 does not assign Program/Tier config to a service. Because Program lifecycle is migration-driven and Tier evaluation runs in core's Tier Projection, the config tables live in `loyalty-core`; other services hold `program_id` as a logical value.
- **R5 — Maker-checker delegated to BEP.** Loyalty no longer implements maker-checker: the `adjustment_request` table is generalised to a `request_type`-agnostic `approval_request`; `point_ledger` and `program_conversion_rule` drop their `maker_user_id`/`checker_user_id` columns (and `point_ledger` drops `ck_ledger_adjusted_4eyes`), keeping only a `bep_approval_ref` / `approval_request_id`. 4-eyes, Job Roles, and caps move to the bank's existing BEP Approval Workflow; the negative-balance policy is unaffected.
