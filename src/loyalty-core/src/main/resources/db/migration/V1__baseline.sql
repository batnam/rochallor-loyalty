-- loyalty-core baseline schema (C4 L3 loyalty-core §5).
-- Owns the 8 tables of the Membership + Ledger shared kernel. No other service has JDBC access.
-- Invariants encoded here, not just in app code:
--   * point_ledger is APPEND-ONLY    -> trigger rejects UPDATE/DELETE
--   * idempotency                    -> UNIQUE(source_ref, entry_type)
--   * one Member per (program, customer)
--   * core_audit_log is tamper-evident (hash-chained) and insert-only

------------------------------------------------------------------------------
-- Append-only / insert-only guard (defence in depth, below the single-writer app rule)
------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION core_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only: % is forbidden', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

------------------------------------------------------------------------------
-- Program / Tier config — SCAFFOLDING NOTE:
-- Authoritative owner is loyalty-earning (BEP "Program / Rule CRUD", L2 §6.3). It is not built
-- yet, so loyalty-core keeps a seeded local copy that the Tier Projection reads. The eventual
-- sync mechanism (config replication vs REST read from earning) is deferred. Seeded in V2.
------------------------------------------------------------------------------
CREATE TABLE program (
    program_id          BIGINT PRIMARY KEY,
    program_code        VARCHAR(32)  NOT NULL UNIQUE,
    qualifying_metric   VARCHAR(24)  NOT NULL,   -- LIFETIME | ROLLING_12_MONTHS | CALENDAR_YEAR
    expiry_months       INT          NOT NULL,
    current_tcs_version INT          NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE tier (
    tier_id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_id             BIGINT      NOT NULL REFERENCES program(program_id),
    tier_code              VARCHAR(32) NOT NULL,
    ordinal                INT         NOT NULL,   -- 1 = lowest
    qualifying_threshold   BIGINT      NOT NULL,   -- Qualifying Balance at which a Member enters this tier
    expiry_months_override INT,                    -- NULL = inherit program.expiry_months (Tier Expiry Override)
    UNIQUE (program_id, tier_code)
);

------------------------------------------------------------------------------
-- member — master Member record per (program, customer). PII-free (customerId only).
-- Single-writer columns split: Membership Aggregate (status), Balance Projection (balances),
-- Tier Projection (current_tier_code).
------------------------------------------------------------------------------
CREATE TABLE member (
    member_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_id           BIGINT      NOT NULL REFERENCES program(program_id),
    customer_id          BIGINT      NOT NULL,
    status               VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE|SUSPENDED_TCS|OPTED_OUT|CLOSED
    redeemable_balance   BIGINT      NOT NULL DEFAULT 0,
    qualifying_balance   BIGINT      NOT NULL DEFAULT 0,
    current_tier_code    VARCHAR(32),
    tcs_version_accepted INT,
    enrolled_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (program_id, customer_id)
);

------------------------------------------------------------------------------
-- point_ledger — append-only source of truth. Only the Ledger Service inserts; never UPDATE/DELETE.
------------------------------------------------------------------------------
CREATE TABLE point_ledger (
    entry_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id           BIGINT      NOT NULL REFERENCES member(member_id),
    program_id          BIGINT      NOT NULL,
    entry_type          VARCHAR(16) NOT NULL,    -- Earned|Redeemed|Expired|Reversed|Adjusted
    qualifying_delta    BIGINT      NOT NULL,
    redeemable_delta    BIGINT      NOT NULL,
    source_ref          VARCHAR(256) NOT NULL,
    reason              VARCHAR(512),
    earn_source_code    VARCHAR(64),
    currency            VARCHAR(8),
    approval_request_id BIGINT,                  -- set when entry_type = Adjusted
    occurred_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_ref, entry_type)
);
CREATE INDEX idx_ledger_member ON point_ledger (member_id, program_id);

CREATE TRIGGER trg_ledger_append_only
    BEFORE UPDATE OR DELETE ON point_ledger
    FOR EACH ROW EXECUTE FUNCTION core_reject_mutation();

------------------------------------------------------------------------------
-- point_reservation — transient two-phase holds. Mutable (not append-only).
------------------------------------------------------------------------------
CREATE TABLE point_reservation (
    reservation_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id       BIGINT      NOT NULL REFERENCES member(member_id),
    program_id      BIGINT      NOT NULL,
    points          BIGINT      NOT NULL CHECK (points > 0),
    reward_id       BIGINT,
    status          VARCHAR(16) NOT NULL DEFAULT 'HELD',  -- HELD|COMMITTED|RELEASED|EXPIRED
    external_ref    VARCHAR(256),
    idempotency_key VARCHAR(128) UNIQUE,
    held_until      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_resv_sweep ON point_reservation (status, held_until);

------------------------------------------------------------------------------
-- point_cohort — per-Earned-entry FIFO consumption tracker. Rebuildable projection.
------------------------------------------------------------------------------
CREATE TABLE point_cohort (
    cohort_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id        BIGINT      NOT NULL REFERENCES point_ledger(entry_id),
    member_id       BIGINT      NOT NULL,
    program_id      BIGINT      NOT NULL,
    original_amount BIGINT      NOT NULL,
    consumed_amount BIGINT      NOT NULL DEFAULT 0,
    expired_amount  BIGINT      NOT NULL DEFAULT 0,
    earned_at       TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL
);
-- FIFO scan: oldest unexhausted cohorts first.
CREATE INDEX idx_cohort_fifo ON point_cohort (member_id, program_id, earned_at);
CREATE INDEX idx_cohort_expiry ON point_cohort (program_id, expires_at);

------------------------------------------------------------------------------
-- approval_request — adjustment / config-activation request lifecycle. 4-eyes delegated to BEP;
-- core stores only bep_approval_ref. Applied entry links back via point_ledger.approval_request_id.
------------------------------------------------------------------------------
CREATE TABLE approval_request (
    approval_request_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id           BIGINT      NOT NULL REFERENCES member(member_id),
    program_id          BIGINT      NOT NULL,
    request_type        VARCHAR(24) NOT NULL,   -- ADJUSTMENT | CONFIG_ACTIVATION
    qualifying_delta    BIGINT,
    redeemable_delta    BIGINT,
    reason              VARCHAR(512),
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING|APPLIED|REJECTED
    bep_approval_ref    VARCHAR(128),
    ledger_entry_id     BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

------------------------------------------------------------------------------
-- core_audit_log — tamper-evident (hash-chained) admin-write trail. Insert-only.
------------------------------------------------------------------------------
CREATE TABLE core_audit_log (
    audit_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_keycloak_id VARCHAR(128),
    action            VARCHAR(64)  NOT NULL,
    entity_type       VARCHAR(64)  NOT NULL,
    entity_id         VARCHAR(64),
    before_json       TEXT,
    after_json        TEXT,
    occurred_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    prev_hash         VARCHAR(64),
    row_hash          VARCHAR(64)  NOT NULL
);

CREATE TRIGGER trg_audit_insert_only
    BEFORE UPDATE OR DELETE ON core_audit_log
    FOR EACH ROW EXECUTE FUNCTION core_reject_mutation();

------------------------------------------------------------------------------
-- outbox — transactional-outbox staging. Inserted in the same txn as the business write;
-- drained by Outbox Relay; rows purged after SENT + 7d (purge job out of scope for v1).
------------------------------------------------------------------------------
CREATE TABLE outbox (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(32)  NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,   -- e.g. loyalty.ledger.PointsEarned
    topic          VARCHAR(128) NOT NULL,
    partition_key  VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,   -- pre-serialized canonical JSON
    status         VARCHAR(8)   NOT NULL DEFAULT 'PENDING',  -- PENDING|SENT
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox (status, created_at);

------------------------------------------------------------------------------
-- shedlock — ShedLock distributed-lock table (owned by the library, not domain data).
------------------------------------------------------------------------------
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
