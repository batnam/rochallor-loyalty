-- Seed the v1 deployment's single Program + its 3-tier ladder (CONTEXT.md "Program", "Tier").
-- SCAFFOLDING: authoritative source is loyalty-earning once built; this is a local copy so
-- loyalty-core runs and tests standalone. Values mirror the seed Program described in DEPLOYMENT.md
-- (ROLLING_12_MONTHS qualifying metric, 24-month default expiry).

INSERT INTO program (program_id, program_code, qualifying_metric, expiry_months, current_tcs_version)
VALUES (1, 'RDB_REWARDS', 'ROLLING_12_MONTHS', 24, 1);

-- Tier ladder (ordinal 1 = lowest). Member enters a tier when qualifying_balance >= threshold.
-- expiry_months_override: NULL inherits program.expiry_months; GOLD gets a longer window (Tier Expiry Override).
INSERT INTO tier (program_id, tier_code, ordinal, qualifying_threshold, expiry_months_override) VALUES
    (1, 'BRONZE', 1,      0, NULL),
    (1, 'SILVER', 2,  50000, 36),
    (1, 'GOLD',   3, 200000, 60);
