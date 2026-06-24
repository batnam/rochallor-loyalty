-- The remaining v1 Reward Types (CONTEXT.md §Reward Type). Each binds to its FulfillmentAdapter via
-- reward_type_code, the same way the four in V2 are bound. All synchronous (is_async=false):
-- MATERIAL_GOODS / CHARITY_DONATION are stub-fulfilled in-process; TIER_BOOST commits the redeemable
-- spend and records the intended qualifying grant (core-side grant wiring is the remaining work).
INSERT INTO reward_type (reward_type_code, display_name, fulfillment_adapter_class, is_async) VALUES
    ('MATERIAL_GOODS',   'Material Goods',   'io.github.batnam.loyalty.redemption.fulfil.adapter.MaterialGoodsAdapter',   false),
    ('CHARITY_DONATION', 'Charity Donation', 'io.github.batnam.loyalty.redemption.fulfil.adapter.CharityDonationAdapter', false),
    ('TIER_BOOST',       'Tier Boost',       'io.github.batnam.loyalty.redemption.fulfil.adapter.TierBoostAdapter',       false);
