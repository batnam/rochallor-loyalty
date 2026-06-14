package io.github.batnam.loyalty.redemption.fulfil;

/**
 * The shipped Reward Types, each fulfilled by one {@link FulfillmentAdapter} (L3 §3.1). The enum name
 * matches the {@code reward_type.reward_type_code} seeded in Flyway V2. Adding a fifth type means a new
 * enum value + a new adapter bean — no Saga change (L3 §7 invariant).
 */
public enum RewardType {
    CASHBACK,
    BILL_PAYMENT_VOUCHER,
    THIRD_PARTY_VOUCHER,
    SWEEPSTAKES
}
