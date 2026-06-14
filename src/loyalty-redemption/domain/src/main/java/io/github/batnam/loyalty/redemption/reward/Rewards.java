package io.github.batnam.loyalty.redemption.reward;

import io.github.batnam.loyalty.redemption.elig.EligibilityRules;

import java.util.Optional;

/**
 * Domain-owned port for the Reward Catalogue's <b>hot-path reads + inventory holds</b>.
 * The Saga resolves a Reward, its eligibility gates, and the atomic inventory reserve/restore through
 * this seam — so the orchestrator depends only on {@code :domain}, never on Spring Data or the JPA
 * {@code Reward} entity.
 *
 * <p>This is the <i>evaluation/fulfilment</i> seam only — the admin authoring path (Reward CRUD, the
 * approval gate, audit) stays on the JPA-backed {@code RewardCatalogue} service, mirroring how
 * loyalty-earning kept rule authoring off its {@code Rules} port.
 */
public interface Rewards {

    /** The Reward as a pure view, or empty if it does not exist. */
    Optional<RewardView> find(long rewardId);

    /** The Reward's eligibility gates, or {@link EligibilityRules#NONE} when none are configured. */
    EligibilityRules eligibilityRulesFor(long rewardId);

    /** Atomic conditional inventory decrement. True if unlimited (no inventory row) or stock was held. */
    boolean tryReserveInventory(long rewardId);

    /** Compensation — return a previously-held unit of inventory. No-op for unlimited rewards. */
    void restoreInventory(long rewardId);
}
