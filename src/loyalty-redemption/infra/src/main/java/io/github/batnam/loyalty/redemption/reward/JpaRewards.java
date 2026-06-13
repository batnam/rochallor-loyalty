package io.github.batnam.loyalty.redemption.reward;

import io.github.batnam.loyalty.redemption.elig.EligibilityRules;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * JPA-backed adapter for the {@link Rewards} port (ADR-0001). Projects the {@code reward} /
 * {@code reward_eligibility} rows to pure {@link RewardView} / {@link EligibilityRules}, and runs the
 * atomic inventory reserve/restore as single conditional UPDATEs. The inventory writes join the Saga's
 * transaction (the orchestrator's {@code @Transactional}); a missing inventory row means unlimited stock.
 */
@Component
public class JpaRewards implements Rewards {

    private final RewardRepository rewards;
    private final RewardEligibilityRepository eligibility;
    private final RewardInventoryRepository inventory;

    public JpaRewards(RewardRepository rewards, RewardEligibilityRepository eligibility,
                      RewardInventoryRepository inventory) {
        this.rewards = rewards;
        this.eligibility = eligibility;
        this.inventory = inventory;
    }

    @Override
    public Optional<RewardView> find(long rewardId) {
        return rewards.findById(rewardId).map(r -> new RewardView(
                r.getRewardId(), r.getProgramId(), r.getRewardTypeCode(),
                r.getPointCost(), r.getFulfillmentParams(), r.isActive()));
    }

    @Override
    public EligibilityRules eligibilityRulesFor(long rewardId) {
        return eligibility.findById(rewardId).map(RewardEligibility::toRules).orElse(EligibilityRules.NONE);
    }

    @Override
    @Transactional
    public boolean tryReserveInventory(long rewardId) {
        if (!inventory.existsById(rewardId)) {
            return true;   // no inventory row => unlimited
        }
        return inventory.tryReserve(rewardId) == 1;
    }

    @Override
    @Transactional
    public void restoreInventory(long rewardId) {
        if (inventory.existsById(rewardId)) {
            inventory.restore(rewardId);
        }
    }
}
