package io.github.batnam.loyalty.core.domain.member;

/**
 * Pure calculation of a Member's <b>Effective Redeemable Balance</b> (CONTEXT.md "Reservation",
 * "Redeemable Balance"): the ledger-projected balance minus the points held by active reservations.
 *
 * <p>This is the bridge between the Member and Reservation aggregates (ADR-0001): the redeem gate is
 * a pure function of two numbers, so it is testable with no database and the Member aggregate does not
 * have to load reservations. The caller supplies {@code activeHeldTotal} (the reservation adapter's
 * {@code sumActiveHeld} aggregate), keeping the O(1) SQL sum and the separate reservation lock.
 */
public final class RedeemableBalance {

    private RedeemableBalance() {
    }

    /** {@code redeemable_balance − SUM(active HELD reservations)}. May be negative. */
    public static long effective(long redeemableBalance, long activeHeldTotal) {
        return redeemableBalance - activeHeldTotal;
    }

    /** Whether {@code points} can be reserved against the effective balance. */
    public static boolean canRedeem(long redeemableBalance, long activeHeldTotal, long points) {
        return effective(redeemableBalance, activeHeldTotal) >= points;
    }
}
