-- loyalty-redemption baseline schema (C4 L3 loyalty-redemption §5).
-- Owns the Reward catalogue + Saga state. No other service has JDBC access.
-- Invariants encoded here, not just in app code:
--   * redemption_audit_log is tamper-evident (hash-chained) and insert-only -> trigger rejects mutation
--   * redemption_idempotency maps an Idempotency-Key to its saga so a submit replay returns the original
--   * reward_inventory is decremented by a single conditional UPDATE (no SELECT-then-UPDATE race)
--   * redemption_saga is single-writer (the Orchestrator); status transitions are explicit

------------------------------------------------------------------------------
-- Insert-only guard for the audit log (defence in depth, below the single-writer app rule)
------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION redemption_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only: % is forbidden', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

------------------------------------------------------------------------------
-- reward_type — platform-seeded Reward Type catalogue (GET /reward-types). Each type binds to a
-- FulfillmentAdapter; the parameter schema documents the shape of reward.fulfillment_params.
------------------------------------------------------------------------------
CREATE TABLE reward_type (
    reward_type_code         VARCHAR(64)  NOT NULL PRIMARY KEY,  -- CASHBACK | BILL_PAYMENT_VOUCHER | ...
    display_name             VARCHAR(128) NOT NULL,
    fulfillment_adapter_class VARCHAR(255) NOT NULL,
    parameter_schema         JSONB,
    is_async                 BOOLEAN      NOT NULL DEFAULT false,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

------------------------------------------------------------------------------
-- reward — per-Program Reward catalogue. DRAFT -> ACTIVE -> ARCHIVED. Never hard-deleted (audit).
-- ACTIVE transition + point_cost change are approval-gated (bep_approval_ref). reward_revision bumps
-- on every point_cost change so already-redeemed rows reference the cost they were redeemed against.
------------------------------------------------------------------------------
CREATE TABLE reward (
    reward_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_id         BIGINT       NOT NULL,
    reward_type_code   VARCHAR(64)  NOT NULL REFERENCES reward_type(reward_type_code),
    reward_revision    INT          NOT NULL DEFAULT 1,
    name               VARCHAR(255) NOT NULL,
    point_cost         BIGINT       NOT NULL CHECK (point_cost > 0),
    status             VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',   -- DRAFT | ACTIVE | ARCHIVED
    fulfillment_params JSONB        NOT NULL DEFAULT '{}'::jsonb,
    effective_from     TIMESTAMPTZ,
    effective_to       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_reward_program ON reward (program_id, status);

------------------------------------------------------------------------------
-- reward_inventory — optional per-Reward inventory cap (limited-edition runs). Atomic conditional
-- decrement on reserve (... WHERE remaining >= 1); restored on release. No row => unlimited.
------------------------------------------------------------------------------
CREATE TABLE reward_inventory (
    reward_id  BIGINT      NOT NULL PRIMARY KEY REFERENCES reward(reward_id),
    total      BIGINT      NOT NULL CHECK (total >= 0),
    remaining  BIGINT      NOT NULL CHECK (remaining >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

------------------------------------------------------------------------------
-- reward_eligibility — per-Reward gates checked read-only on the hot path before any balance is held.
-- NULL column => that gate is not enforced.
------------------------------------------------------------------------------
CREATE TABLE reward_eligibility (
    reward_id        BIGINT      NOT NULL PRIMARY KEY REFERENCES reward(reward_id),
    min_tier_ordinal INT,                  -- member tier ordinal must be >= this
    segment          VARCHAR(64),          -- required marketing segment, if any
    currency         VARCHAR(8),           -- required member currency, if any
    per_member_cap   INT,                  -- max successful redemptions of this reward per member
    min_tenure_days  INT,                  -- member must have been enrolled at least this long
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

------------------------------------------------------------------------------
-- redemption_saga — the two-phase Saga state machine. Single-writer = Orchestrator.
-- RESERVED -> FULFILLING -> COMMITTED | RELEASED | FAILED. Keyed for resume by external_ref.
------------------------------------------------------------------------------
CREATE TABLE redemption_saga (
    saga_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_id       BIGINT       NOT NULL,
    member_id        BIGINT       NOT NULL,
    reward_id        BIGINT       NOT NULL,
    reward_type_code VARCHAR(64)  NOT NULL,
    point_cost       BIGINT       NOT NULL,
    reservation_id   BIGINT,                              -- core reservation; null until reserve()
    status           VARCHAR(16)  NOT NULL,               -- RESERVED | FULFILLING | COMMITTED | RELEASED | FAILED
    external_ref     VARCHAR(255),                        -- adapter outcome ref (disbursement / voucher code)
    ledger_entry_id  BIGINT,                              -- non-null once COMMITTED
    failure_reason   VARCHAR(64),                         -- set when status=FAILED
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Resume Consumer looks a saga up by the adapter's external_ref.
CREATE INDEX idx_saga_external_ref ON redemption_saga (external_ref);

------------------------------------------------------------------------------
-- redemption_idempotency — maps a submit Idempotency-Key to the saga it created, so a replay returns
-- the original outcome instead of running the Saga twice.
------------------------------------------------------------------------------
CREATE TABLE redemption_idempotency (
    idempotency_key VARCHAR(128) NOT NULL PRIMARY KEY,
    saga_id         BIGINT       NOT NULL REFERENCES redemption_saga(saga_id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

------------------------------------------------------------------------------
-- redemption_audit_log — tamper-evident (hash-chained) admin-write trail. Insert-only.
------------------------------------------------------------------------------
CREATE TABLE redemption_audit_log (
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

CREATE TRIGGER trg_redemption_audit_insert_only
    BEFORE UPDATE OR DELETE ON redemption_audit_log
    FOR EACH ROW EXECUTE FUNCTION redemption_reject_mutation();

------------------------------------------------------------------------------
-- outbox — transactional-outbox staging for loyalty.redemption.*. Inserted in the same txn as the
-- Saga write; drained by Outbox Relay; rows purged after SENT + 7d (out of v1 scope).
------------------------------------------------------------------------------
CREATE TABLE outbox (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(32)  NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,   -- RedemptionCompleted | RedemptionFailed
    topic          VARCHAR(128) NOT NULL,
    partition_key  VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,   -- pre-serialized canonical JSON
    status         VARCHAR(8)   NOT NULL DEFAULT 'PENDING',  -- PENDING | SENT
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
