package io.github.batnam.loyalty.redemption.elig;

import io.github.batnam.loyalty.redemption.elig.EligibilityDecision.RejectReason;

import java.util.Objects;

/**
 * Eligibility Engine (L3 §3.2). A pure decision function: given the Reward's gates, a snapshot of the
 * Member (from loyalty-core's projection) and the Member's prior-redemption count, decide whether the
 * redemption may proceed — <b>before</b> any balance is reserved. Cheap attribute gates are evaluated
 * before the balance check so a wrong-tier reject never prices the redemption.
 *
 * <p>Stateless and I/O-free, so the Orchestrator can call it on the hot path and the
 * {@code listEligibleRewards} read path can call it per Reward without side effects.
 *
 * <p>Framework-free since  (the {@code @Component} was removed); it is wired as a {@code @Bean}
 * in the app ring ({@code DomainConfig}).
 */
public class EligibilityEngine {

    /**
     * Member attributes the gates compare against. Built from core's Member projection; attributes core
     * does not expose in v1 (segment, currency, tier ordinal, tenure) arrive {@code null} and simply
     * cause the corresponding gate — if the Reward even sets one — to reject (fail-closed).
     */
    public record MemberSnapshot(
            Integer tierOrdinal,
            String segment,
            String currency,
            long redeemableBalance,
            Integer tenureDays
    ) {}

    public EligibilityDecision check(boolean rewardActive, EligibilityRules rules,
                                     MemberSnapshot member, long pointCost, int priorRedemptionCount) {
        if (!rewardActive) {
            return EligibilityDecision.reject(RejectReason.REWARD_NOT_ACTIVE);
        }
        if (rules.minTierOrdinal() != null
                && (member.tierOrdinal() == null || member.tierOrdinal() < rules.minTierOrdinal())) {
            return EligibilityDecision.reject(RejectReason.WRONG_TIER);
        }
        if (rules.segment() != null && !rules.segment().equals(member.segment())) {
            return EligibilityDecision.reject(RejectReason.WRONG_SEGMENT);
        }
        if (rules.currency() != null && !rules.currency().equals(member.currency())) {
            return EligibilityDecision.reject(RejectReason.WRONG_CURRENCY);
        }
        if (rules.minTenureDays() != null
                && (member.tenureDays() == null || member.tenureDays() < rules.minTenureDays())) {
            return EligibilityDecision.reject(RejectReason.INSUFFICIENT_TENURE);
        }
        if (rules.perMemberCap() != null && priorRedemptionCount >= rules.perMemberCap()) {
            return EligibilityDecision.reject(RejectReason.PER_MEMBER_CAP);
        }
        if (member.redeemableBalance() < pointCost) {
            return EligibilityDecision.reject(RejectReason.INSUFFICIENT_BALANCE);
        }
        return EligibilityDecision.OK;
    }

    /** Convenience for read paths that only have rules + snapshot and want a boolean. */
    public boolean isEligible(boolean rewardActive, EligibilityRules rules,
                              MemberSnapshot member, long pointCost, int priorRedemptionCount) {
        return check(rewardActive, Objects.requireNonNull(rules), member, pointCost, priorRedemptionCount)
                .eligible();
    }
}
