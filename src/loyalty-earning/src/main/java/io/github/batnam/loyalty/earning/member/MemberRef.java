package io.github.batnam.loyalty.earning.member;

/**
 * The resolution of a customer-scoped EarnEvent to a concrete loyalty-core Member, returned by
 * {@code GET /members/lookup} on core. v1 is single-Program, 1:1 customer↔member. PII-free.
 */
public record MemberRef(Long memberId, Long programId, String status) {

    /** Only ACTIVE members accrue points; SUSPENDED_TCS / OPTED_OUT / CLOSED are skipped upstream. */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
