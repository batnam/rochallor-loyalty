package io.github.batnam.loyalty.redemption.reward;

import io.github.batnam.loyalty.redemption.elig.EligibilityRules;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per-Reward eligibility gates ({@code reward_eligibility}, PK = reward_id). Read-only on the hot path.
 * {@link #toRules()} projects it to the pure {@link EligibilityRules} the Eligibility Engine consumes.
 */
@Entity
@Table(name = "reward_eligibility")
public class RewardEligibility {

    @Id
    @Column(name = "reward_id")
    private Long rewardId;

    @Column(name = "min_tier_ordinal")
    private Integer minTierOrdinal;

    @Column(name = "segment")
    private String segment;

    @Column(name = "currency")
    private String currency;

    @Column(name = "per_member_cap")
    private Integer perMemberCap;

    @Column(name = "min_tenure_days")
    private Integer minTenureDays;

    protected RewardEligibility() {
    }

    public static RewardEligibility of(Long rewardId, Integer minTierOrdinal, String segment,
                                       String currency, Integer perMemberCap, Integer minTenureDays) {
        RewardEligibility e = new RewardEligibility();
        e.rewardId = rewardId;
        e.minTierOrdinal = minTierOrdinal;
        e.segment = segment;
        e.currency = currency;
        e.perMemberCap = perMemberCap;
        e.minTenureDays = minTenureDays;
        return e;
    }

    public EligibilityRules toRules() {
        return new EligibilityRules(minTierOrdinal, segment, currency, perMemberCap, minTenureDays);
    }

    public Long getRewardId() { return rewardId; }
    public Integer getPerMemberCap() { return perMemberCap; }
}
