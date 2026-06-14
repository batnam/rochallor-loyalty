-- v1 Earn Source catalogue (loyalty-earning.yaml EarnSource example; open-items-v1-earning §2).
-- New Earn Source = a new translation in loyalty-integration-bridge, so this set mirrors the
-- canonical `source` values the Bridge emits on loyalty.earn.translated.v1.
INSERT INTO earn_source (earn_source_code, display_name, active_by_default) VALUES
    ('CARD_SPEND',          'Card spend',          true),
    ('BILL_PAYMENT',        'Bill payment',        true),
    ('FUND_TRANSFER',       'Fund transfer',       true),
    ('TOPUP',               'Top-up',              true),
    ('TERM_DEPOSIT_OPENED', 'Term deposit opened', true);
