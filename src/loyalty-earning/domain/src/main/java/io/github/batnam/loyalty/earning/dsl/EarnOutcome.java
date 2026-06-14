package io.github.batnam.loyalty.earning.dsl;

/**
 * The result of evaluating one Earning Rule against one event: the points to award to each balance.
 * Pure value; the Rule Engine turns a non-zero outcome into a {@code POST /ledger/earn} call.
 */
public record EarnOutcome(long qualifyingDelta, long redeemableDelta) {

    public static final EarnOutcome ZERO = new EarnOutcome(0, 0);

    public boolean isZero() {
        return qualifyingDelta == 0 && redeemableDelta == 0;
    }
}
