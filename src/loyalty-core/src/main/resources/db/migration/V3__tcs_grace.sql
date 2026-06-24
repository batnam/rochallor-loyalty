-- T&Cs version re-acceptance: the 30-day grace window (CONTEXT.md "T&Cs Version").
-- When program.current_tcs_version advances, a Member whose tcs_version_accepted is behind enters a
-- grace window measured from the instant the CURRENT version became effective. We record that instant
-- per Program here. Existing rows (incl. the V2-seeded Program) backfill via the DEFAULT now().
ALTER TABLE program
    ADD COLUMN tcs_version_effective_at TIMESTAMPTZ NOT NULL DEFAULT now();
