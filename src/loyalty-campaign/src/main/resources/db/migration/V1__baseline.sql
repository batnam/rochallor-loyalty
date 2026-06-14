-- loyalty-campaign baseline schema (C4 L3 loyalty-campaign §5).
-- Owns Campaign + Drawing definitions, the Entry ledger and immutable Winner records. No other service
-- has JDBC access. Invariants encoded here, not just in app code:
--   * campaign_audit_log is tamper-evident (hash-chained) and insert-only -> trigger rejects mutation
--   * winner_record is immutable (insert-only trigger) and UNIQUE(drawing_id, winner_index) blocks
--     duplicate selection of the same winner slot
--   * drawing_entry.idempotency_key is UNIQUE -> a Saga replay cannot enter a Member twice; entries are
--     window-gated by a single conditional INSERT (no SELECT-then-INSERT race)
--   * drawing / campaign status transitions are explicit (single-writer services)

------------------------------------------------------------------------------
-- Insert-only guard for append-only tables (defence in depth, below the single-writer app rule)
------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION campaign_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only: % is forbidden', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

------------------------------------------------------------------------------
-- campaign — per-Program marketing Campaign (multiplier rule + validity window). Authored in BEP.
-- DRAFT -> SCHEDULED -> LIVE -> ENDED -> ARCHIVED. The LIVE transition is approval-gated when the
-- Campaign carries earning multipliers (it is economic). multiplier_rule is *exposed* to loyalty-earning;
-- campaign never evaluates it.
------------------------------------------------------------------------------
CREATE TABLE campaign (
    campaign_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_id      BIGINT       NOT NULL,
    program_code    VARCHAR(32)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',  -- DRAFT|SCHEDULED|LIVE|ENDED|ARCHIVED
    starts_at       TIMESTAMPTZ  NOT NULL,
    ends_at         TIMESTAMPTZ  NOT NULL,
    multiplier_rule JSONB,                                   -- exposed to earning; NULL => non-economic
    target_segment  JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_campaign_program ON campaign (program_id, status);

------------------------------------------------------------------------------
-- drawing — a sweepstakes Drawing under a Campaign: prize, entry window, draw time, selection strategy.
-- OPEN -> CLOSED (winners selected) | VOID (zero entries). draw_at drives the in-pod scheduler.
------------------------------------------------------------------------------
CREATE TABLE drawing (
    drawing_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id        BIGINT       NOT NULL REFERENCES campaign(campaign_id),
    program_id         BIGINT       NOT NULL,
    prize              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    entry_window_start TIMESTAMPTZ  NOT NULL,
    entry_window_end   TIMESTAMPTZ  NOT NULL,
    draw_at            TIMESTAMPTZ  NOT NULL,
    selection_strategy VARCHAR(16)  NOT NULL DEFAULT 'SEEDED_RNG', -- SEEDED_RNG|WEIGHTED|FIRST_N
    winners_count      INT          NOT NULL DEFAULT 1 CHECK (winners_count >= 1),
    status             VARCHAR(16)  NOT NULL DEFAULT 'OPEN',  -- OPEN|CLOSED|VOID
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- The scheduler polls for OPEN drawings whose draw_at has passed.
CREATE INDEX idx_drawing_due ON drawing (status, draw_at);
CREATE INDEX idx_drawing_campaign ON drawing (campaign_id);

------------------------------------------------------------------------------
-- drawing_entry — one row per Member entry per Drawing. idempotency_key (derived from the redemption
-- Saga key) is UNIQUE so a replay is a no-op. weight is frozen at entry time for WEIGHTED selection.
------------------------------------------------------------------------------
CREATE TABLE drawing_entry (
    entry_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    drawing_id      BIGINT       NOT NULL REFERENCES drawing(drawing_id),
    member_id       BIGINT       NOT NULL,
    saga_id         BIGINT,                              -- calling redemption Saga (audit linking)
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    weight          INT          NOT NULL DEFAULT 1 CHECK (weight >= 1),  -- frozen; WEIGHTED only
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_entry_drawing ON drawing_entry (drawing_id, entry_id);

------------------------------------------------------------------------------
-- winner_record — one immutable row PER winner (K per drawing). Audit-replayable: row + HMAC secret +
-- frozen entry order/weights reproduce SEEDED_RNG/WEIGHTED selections (seed_hex NULL for FIRST_N).
-- UNIQUE(drawing_id, winner_index) blocks duplicate selection of a slot.
------------------------------------------------------------------------------
CREATE TABLE winner_record (
    winner_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    drawing_id    BIGINT       NOT NULL REFERENCES drawing(drawing_id),
    member_id     BIGINT       NOT NULL,
    winner_index  INT          NOT NULL,                 -- index into entry set ordered by entry_id
    seed_hex      VARCHAR(64),                            -- NULL for FIRST_N (no RNG)
    drawn_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (drawing_id, winner_index)
);
CREATE INDEX idx_winner_drawing ON winner_record (drawing_id);

CREATE TRIGGER trg_winner_record_insert_only
    BEFORE UPDATE OR DELETE ON winner_record
    FOR EACH ROW EXECUTE FUNCTION campaign_reject_mutation();

------------------------------------------------------------------------------
-- campaign_audit_log — tamper-evident (hash-chained) admin-write trail. Insert-only.
------------------------------------------------------------------------------
CREATE TABLE campaign_audit_log (
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

CREATE TRIGGER trg_campaign_audit_insert_only
    BEFORE UPDATE OR DELETE ON campaign_audit_log
    FOR EACH ROW EXECUTE FUNCTION campaign_reject_mutation();

------------------------------------------------------------------------------
-- outbox — transactional-outbox staging for loyalty.campaign.*. Inserted in the same txn as the Drawing
-- write; drained by Outbox Relay; rows purged after SENT + 7d (out of v1 scope).
------------------------------------------------------------------------------
CREATE TABLE outbox (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(32)  NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,   -- DrawingCompleted | WinnerSelected | DrawingVoid
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
