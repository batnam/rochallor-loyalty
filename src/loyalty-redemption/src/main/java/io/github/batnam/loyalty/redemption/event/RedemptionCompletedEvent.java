package io.github.batnam.loyalty.redemption.event;

import io.github.batnam.loyalty.redemption.saga.RedemptionSaga;

import java.time.Instant;

/**
 * {@code loyalty.redemption.completed.v1} envelope (asyncapi/loyalty-redemption.yaml). Emitted via the
 * outbox when a redemption commits (sync or async). {@code eventId} is derived from the saga id so the
 * event is stably deduplicable downstream.
 */
public record RedemptionCompletedEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        Long programId,
        int schemaVersion,
        Long redemptionId,
        Long memberId,
        Long rewardId,
        String rewardTypeCode,
        String externalRef,
        Instant completedAt
) {
    public static RedemptionCompletedEvent from(RedemptionSaga s) {
        Instant now = Instant.now();
        return new RedemptionCompletedEvent(
                "redemption-completed-" + s.getSagaId(), "RedemptionCompleted", now,
                s.getProgramId(), 1, s.getSagaId(), s.getMemberId(), s.getRewardId(),
                s.getRewardTypeCode(), s.getExternalRef(), now);
    }
}
