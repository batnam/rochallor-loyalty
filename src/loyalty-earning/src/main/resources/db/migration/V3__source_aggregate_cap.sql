-- Source-Aggregate Cap (CONTEXT.md "Source-Aggregate Cap"): an OPTIONAL per-(Program, Earn Source)
-- cap on the TOTAL points awardable across ALL earning rules for that source over a window. Distinct
-- from a per-Rule cap; when both are configured the more restrictive applies. Enforced outside the DSL
-- (earning-rule.schema.json note) by the Rule Engine, reusing the cap_counter atomic-decrement machinery.
--
-- Config only — nullable caps, absence => uncapped. SEEDED EMPTY on purpose: existing behaviour and
-- tests are unchanged unless a deployment configures a cap here.
CREATE TABLE earn_source_cap (
    program_id       BIGINT      NOT NULL,
    earn_source_code VARCHAR(64) NOT NULL REFERENCES earn_source(earn_source_code),
    daily_cap        BIGINT,            -- NULL => no daily cap
    monthly_cap      BIGINT,            -- NULL => no monthly cap
    lifetime_cap     BIGINT,            -- NULL => no lifetime cap
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (program_id, earn_source_code)
);

-- The source-aggregate counter reuses cap_counter, keyed at the SOURCE level via a synthetic
-- rule_id = -earn_source_id (real rule_ids are positive IDENTITY values, so no collision). Same
-- (program_id, member_id, window_key) shape, so the existing conditional UPDATE and nightly purge
-- apply unchanged. No schema change to cap_counter is needed.
