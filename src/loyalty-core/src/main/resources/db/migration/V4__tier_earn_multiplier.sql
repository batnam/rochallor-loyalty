-- Per-Tier earn multiplier (CONTEXT.md "Tier": tiers carry benefits including an earn multiplier).
-- loyalty-earning scales accrued points by the member's current-tier multiplier. DEFAULT 1.000 means
-- no behaviour change until a deployment configures real multipliers (V2-seeded tiers stay at 1.0).
ALTER TABLE tier
    ADD COLUMN earn_multiplier NUMERIC(6,3) NOT NULL DEFAULT 1.000;
