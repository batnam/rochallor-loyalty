package io.github.batnam.loyalty.redemption.elig;

/**
 * Outcome of an eligibility check: either eligible, or the single (first-failing) reason it was not.
 * The {@link RejectReason} maps onto the {@code 409} {@code Problem.code} the API returns and the
 * {@code RedemptionFailed.reason} the outbox emits.
 */
public record EligibilityDecision(boolean eligible, RejectReason reason) {

    public enum RejectReason {
        REWARD_NOT_ACTIVE,
        WRONG_TIER,
        WRONG_SEGMENT,
        WRONG_CURRENCY,
        INSUFFICIENT_TENURE,
        PER_MEMBER_CAP,
        INSUFFICIENT_BALANCE
    }

    public static final EligibilityDecision OK = new EligibilityDecision(true, null);

    public static EligibilityDecision reject(RejectReason reason) {
        return new EligibilityDecision(false, reason);
    }
}
