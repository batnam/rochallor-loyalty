package io.github.batnam.loyalty.redemption.reward;

/** Reward lifecycle (loyalty-redemption.yaml). DRAFT -> ACTIVE -> ARCHIVED; never hard-deleted. */
public enum RewardStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
