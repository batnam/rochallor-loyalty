-- loyalty-earning baseline schema (C4 L3 loyalty-earning §5).
-- Owns the Rule Engine's tables. No other service has JDBC access.
-- Invariants encoded here, not just in app code:
--   * earning_audit_log is tamper-evident (hash-chained) and insert-only -> trigger rejects mutation
--   * idempotency_key short-circuits replays before any rule work (event_id PK)
--   * cap_counter is decremented by a single conditional UPDATE (no SELECT-then-UPDATE race)

------------------------------------------------------------------------------
-- Insert-only guard for the audit log (defence in depth, below the single-writer app rule)
------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION earning_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only: % is forbidden', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

------------------------------------------------------------------------------
-- earn_source — catalogue of accepted Earn Source codes. Tied to upstream producer topics;
-- a new Earn Source = a new translation in loyalty-integration-bridge.
------------------------------------------------------------------------------
CREATE TABLE earn_source (
    earn_source_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    earn_source_code  VARCHAR(64)  NOT NULL UNIQUE,   -- CARD_SPEND | BILL_PAYMENT | FUND_TRANSFER | ...
    display_name      VARCHAR(128) NOT NULL,
    active_by_default BOOLEAN      NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

------------------------------------------------------------------------------
-- earning_rule — per-Program Rules in the constrained JSON DSL. DRAFT -> ACTIVE -> ARCHIVED.
-- Never deleted (audit). dsl_json is schema-validated at save against earning-rule.schema.json.
------------------------------------------------------------------------------
CREATE TABLE earning_rule (
    rule_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_id     BIGINT      NOT NULL,
    earn_source_id BIGINT      NOT NULL REFERENCES earn_source(earn_source_id),
    dsl_json       JSONB       NOT NULL,
    version        INT         NOT NULL DEFAULT 1,
    status         VARCHAR(16) NOT NULL DEFAULT 'DRAFT',   -- DRAFT | ACTIVE | ARCHIVED
    effective_from TIMESTAMPTZ,
    effective_to   TIMESTAMPTZ,
    campaign_id    BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Hot-path resolution: active rules for a (program, source) at evaluation time.
CREATE INDEX idx_rule_resolve ON earning_rule (program_id, earn_source_id, status);

------------------------------------------------------------------------------
-- cap_counter — per-(program, rule, member, window) remaining-points counter. Decremented by a
-- single conditional UPDATE (... WHERE remaining >= :n); 0 rows affected => cap exhausted.
------------------------------------------------------------------------------
CREATE TABLE cap_counter (
    program_id  BIGINT      NOT NULL,
    rule_id     BIGINT      NOT NULL,
    member_id   BIGINT      NOT NULL,
    window_key  VARCHAR(32) NOT NULL,   -- DAY:2026-05-30 | MONTH:2026-05 | LIFE  (CapService derives)
    remaining   BIGINT      NOT NULL,   -- initialised to the cap limit on first use
    expires_at  TIMESTAMPTZ,            -- NULL for LIFE; drives the nightly purge
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (program_id, rule_id, member_id, window_key)
);
CREATE INDEX idx_cap_purge ON cap_counter (expires_at);

------------------------------------------------------------------------------
-- idempotency_key — one row per processed eventId. Cheap short-circuit before any rule work.
-- TTL-purged after 90 days (Kafka retention + grace) — purge job out of scope for v1.
------------------------------------------------------------------------------
CREATE TABLE idempotency_key (
    event_id     VARCHAR(128) NOT NULL PRIMARY KEY,
    member_id    BIGINT       NOT NULL,
    program_id   BIGINT       NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

------------------------------------------------------------------------------
-- earning_audit_log — tamper-evident (hash-chained) admin-write trail. Insert-only.
------------------------------------------------------------------------------
CREATE TABLE earning_audit_log (
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

CREATE TRIGGER trg_earning_audit_insert_only
    BEFORE UPDATE OR DELETE ON earning_audit_log
    FOR EACH ROW EXECUTE FUNCTION earning_reject_mutation();

------------------------------------------------------------------------------
-- outbox — transactional-outbox staging for loyalty.earning.points_earned.v1. Inserted in the same
-- txn as the business write; drained by Outbox Relay; rows purged after SENT + 7d (out of v1 scope).
------------------------------------------------------------------------------
CREATE TABLE outbox (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(32)  NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,   -- e.g. PointsEarned
    topic          VARCHAR(128) NOT NULL,
    partition_key  VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,   -- pre-serialized canonical JSON
    status         VARCHAR(8)   NOT NULL DEFAULT 'PENDING',  -- PENDING | SENT
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox (status, created_at);

------------------------------------------------------------------------------
-- earn_event_log — replay store. Every consumed EarnEvent is persisted (post-Bridge translation)
-- so the dry-run evaluator can replay a historical window through the same DSL Interpreter the hot
-- path uses, with no side effects.
------------------------------------------------------------------------------
CREATE TABLE earn_event_log (
    event_id    VARCHAR(128) NOT NULL PRIMARY KEY,
    source      VARCHAR(64)  NOT NULL,   -- earn_source_code carried on the event
    customer_id BIGINT       NOT NULL,
    payload     JSONB        NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_replay_window ON earn_event_log (source, occurred_at);

------------------------------------------------------------------------------
-- shedlock — ShedLock distributed-lock table (owned by the library, not domain data).
------------------------------------------------------------------------------
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
