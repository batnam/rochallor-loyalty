package io.github.batnam.loyalty.earning.member;

import java.math.BigDecimal;

/**
 * The resolution of a customer-scoped EarnEvent to a concrete loyalty-core Member, returned by
 * {@code GET /members/lookup} on core. v1 is single-Program, 1:1 customer↔member. PII-free.
 *
 * <p>{@code earnMultiplier} is the member's tier-benefit earn multiplier (e.g. {@code 1.000}); it is
 * fed to the DSL Interpreter and only bites on rules with {@code tierMultiplier:true}. It defaults to
 * {@code 1.0} when absent/null, so a core that has not yet deployed the field is neutral.
 */
public record MemberRef(Long memberId, Long programId, String status, BigDecimal earnMultiplier) {

    public MemberRef {
        if (earnMultiplier == null) {
            earnMultiplier = BigDecimal.ONE;
        }
    }

    /** Only ACTIVE members accrue points; SUSPENDED_TCS / OPTED_OUT / CLOSED are skipped upstream. */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
