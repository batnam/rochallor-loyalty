package io.github.batnam.loyalty.redemption.elig;

import io.github.batnam.loyalty.redemption.elig.EligibilityDecision.RejectReason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure {@link EligibilityEngine} (L3 §3.2 — "cheap rejects shouldn't tie up
 * balance"). Each gate is enforced only when the Reward sets it; the engine returns the FIRST failing
 * reason, and balance is checked last (after the cheap attribute gates). No I/O — the Orchestrator
 * supplies the member snapshot (from core's projection) and prior-redemption count.
 */
class EligibilityEngineTest {

    private final EligibilityEngine engine = new EligibilityEngine();

    private static final long COST = 5000;

    /** A member who clears every gate with room to spare. */
    private static EligibilityEngine.MemberSnapshot goldMember() {
        return new EligibilityEngine.MemberSnapshot(3, "AFFLUENT", "VND", 100_000, 400);
    }

    /** No gates set => only reward-active + balance matter. */
    private static EligibilityRules noGates() {
        return new EligibilityRules(null, null, null, null, null);
    }

    @Test
    void allGatesClearedReturnsOk() {
        EligibilityRules rules = new EligibilityRules(2, "AFFLUENT", "VND", 5, 365);
        EligibilityDecision d = engine.check(true, rules, goldMember(), COST, 0);
        assertThat(d.eligible()).isTrue();
        assertThat(d.reason()).isNull();
    }

    @Test
    void inactiveRewardIsRejectedFirst() {
        // Even a member who would otherwise pass everything is rejected if the reward isn't ACTIVE.
        EligibilityDecision d = engine.check(false, noGates(), goldMember(), COST, 0);
        assertThat(d.reason()).isEqualTo(RejectReason.REWARD_NOT_ACTIVE);
    }

    @Test
    void tierBelowMinimumIsRejected() {
        EligibilityRules rules = new EligibilityRules(5, null, null, null, null);   // needs ordinal >= 5
        EligibilityDecision d = engine.check(true, rules, goldMember(), COST, 0);   // member is 3
        assertThat(d.reason()).isEqualTo(RejectReason.WRONG_TIER);
    }

    @Test
    void wrongSegmentIsRejected() {
        EligibilityRules rules = new EligibilityRules(null, "STAFF", null, null, null);
        EligibilityDecision d = engine.check(true, rules, goldMember(), COST, 0);
        assertThat(d.reason()).isEqualTo(RejectReason.WRONG_SEGMENT);
    }

    @Test
    void wrongCurrencyIsRejected() {
        EligibilityRules rules = new EligibilityRules(null, null, "USD", null, null);
        EligibilityDecision d = engine.check(true, rules, goldMember(), COST, 0);
        assertThat(d.reason()).isEqualTo(RejectReason.WRONG_CURRENCY);
    }

    @Test
    void insufficientTenureIsRejected() {
        EligibilityRules rules = new EligibilityRules(null, null, null, null, 730);   // needs 2 years
        EligibilityDecision d = engine.check(true, rules, goldMember(), COST, 0);     // member has 400 days
        assertThat(d.reason()).isEqualTo(RejectReason.INSUFFICIENT_TENURE);
    }

    @Test
    void perMemberCapReachedIsRejected() {
        EligibilityRules rules = new EligibilityRules(null, null, null, 2, null);     // max 2 per member
        EligibilityDecision d = engine.check(true, rules, goldMember(), COST, 2);     // already redeemed 2
        assertThat(d.reason()).isEqualTo(RejectReason.PER_MEMBER_CAP);
    }

    @Test
    void insufficientBalanceIsRejectedLast() {
        EligibilityEngine.MemberSnapshot poor =
                new EligibilityEngine.MemberSnapshot(3, "AFFLUENT", "VND", 4999, 400);
        EligibilityDecision d = engine.check(true, noGates(), poor, COST, 0);
        assertThat(d.reason()).isEqualTo(RejectReason.INSUFFICIENT_BALANCE);
    }

    @Test
    void exactBalanceIsEligible() {
        EligibilityEngine.MemberSnapshot exact =
                new EligibilityEngine.MemberSnapshot(3, "AFFLUENT", "VND", COST, 400);
        assertThat(engine.check(true, noGates(), exact, COST, 0).eligible()).isTrue();
    }

    @Test
    void attributeGatesAreCheckedBeforeBalance() {
        // Member fails BOTH tier and balance — the cheaper tier gate must win (no point pricing a reject).
        EligibilityEngine.MemberSnapshot poorLowTier =
                new EligibilityEngine.MemberSnapshot(1, "AFFLUENT", "VND", 0, 400);
        EligibilityRules rules = new EligibilityRules(5, null, null, null, null);
        assertThat(engine.check(true, rules, poorLowTier, COST, 0).reason())
                .isEqualTo(RejectReason.WRONG_TIER);
    }
}
