package io.github.batnam.loyalty.redemption.elig;

/**
 * The per-Reward eligibility gates (a pure value mirror of the {@code reward_eligibility} row, so the
 * {@link EligibilityEngine} stays free of JPA). A {@code null} field means that gate is not enforced.
 */
public record EligibilityRules(
        Integer minTierOrdinal,
        String segment,
        String currency,
        Integer perMemberCap,
        Integer minTenureDays
) {
    /** No gates — only reward-active + balance are decisive. */
    public static final EligibilityRules NONE = new EligibilityRules(null, null, null, null, null);
}
