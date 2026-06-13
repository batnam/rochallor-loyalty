package io.github.batnam.loyalty.redemption.reward;

/**
 * The Reward attributes the Saga needs on the hot path, as a pure value (ADR-0001) — so the
 * orchestrator depends on {@code :domain}, not the JPA {@code Reward} entity. {@code fulfillmentParams}
 * is the raw JSON blob the {@code SagaContext} carries to the adapter (parsing stays in the app ring,
 * which already owns the Jackson mapper).
 */
public record RewardView(
        long rewardId,
        long programId,
        String rewardTypeCode,
        long pointCost,
        String fulfillmentParams,
        boolean active
) {
}
