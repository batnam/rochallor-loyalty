-- A sample LIVE Campaign + an OPEN Drawing for Program 1 (matches core's seeded program) for local/dev.
-- Entry window open, draw_at well in the future so the scheduler leaves it alone until a dev triggers it.
INSERT INTO campaign (program_id, program_code, name, status, starts_at, ends_at, multiplier_rule) VALUES
    (1, 'RETAIL', 'Dining Double Points May', 'LIVE',
     '2026-05-01T00:00:00Z', '2026-06-30T23:59:59Z', '{"category":"DINING","multiplier":2}'::jsonb);

INSERT INTO drawing (campaign_id, program_id, prize, entry_window_start, entry_window_end, draw_at,
                     selection_strategy, winners_count, status) VALUES
    ((SELECT campaign_id FROM campaign WHERE name = 'Dining Double Points May'),
     1, '{"prizeRewardId":1,"label":"VND 1,000,000 Cashback"}'::jsonb,
     '2026-05-01T00:00:00Z', '2026-06-29T23:59:59Z', '2026-06-30T12:00:00Z',
     'SEEDED_RNG', 3, 'OPEN');
