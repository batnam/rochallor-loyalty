-- Platform-seeded Reward Type catalogue (GET /reward-types). Each binds to a FulfillmentAdapter.
-- is_async=true marks the partner-dependent path that returns FULFILLING and resumes via Kafka.
INSERT INTO reward_type (reward_type_code, display_name, fulfillment_adapter_class, is_async) VALUES
    ('CASHBACK',             'Cashback to CASA',        'io.github.batnam.loyalty.redemption.fulfil.adapter.CashbackAdapter',           false),
    ('BILL_PAYMENT_VOUCHER', 'Bill-Payment Voucher',    'io.github.batnam.loyalty.redemption.fulfil.adapter.BillPaymentVoucherAdapter', false),
    ('THIRD_PARTY_VOUCHER',  '3rd-Party Voucher',       'io.github.batnam.loyalty.redemption.fulfil.adapter.ThirdPartyVoucherAdapter',  true),
    ('SWEEPSTAKES',          'Sweepstakes Entry',       'io.github.batnam.loyalty.redemption.fulfil.adapter.SweepstakesAdapter',        false);

-- A couple of sample ACTIVE rewards for Program 1 (matches core's seeded program) for local/dev.
-- reward_id values are IDENTITY-assigned; eligibility/inventory below reference them by name lookup.
INSERT INTO reward (program_id, reward_type_code, name, point_cost, status, fulfillment_params) VALUES
    (1, 'CASHBACK',            'VND 50,000 Cashback', 5000,  'ACTIVE', '{"amount":50000,"currency":"VND"}'::jsonb),
    (1, 'THIRD_PARTY_VOUCHER', 'Coffee Voucher',      3000,  'ACTIVE', '{"sku":"COFFEE-REGULAR"}'::jsonb);
