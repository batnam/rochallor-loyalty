package io.github.batnam.loyalty.redemption.event;

import io.github.batnam.loyalty.redemption.saga.RedemptionSaga;

import java.time.Instant;

/**
 * {@code loyalty.redemption.failed.v1} envelope (asyncapi/loyalty-redemption.yaml). Emitted via the
 * outbox when a redemption is released/failed (partner failure or TTL expiry) and the held points are
 * returned. {@code reason} is one of {@code RESERVATION_TTL_EXPIRED | PARTNER_FAILURE |
 * ELIGIBILITY_REJECTED}.
 */
public record RedemptionFailedEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        Long programId,
        int schemaVersion,
        Long redemptionId,
        Long memberId,
        Long rewardId,
        String reason,
        Instant failedAt
) {
    public static RedemptionFailedEvent from(RedemptionSaga s, String reason) {
        Instant now = Instant.now();
        return new RedemptionFailedEvent(
                "redemption-failed-" + s.getSagaId(), "RedemptionFailed", now,
                s.getProgramId(), 1, s.getSagaId(), s.getMemberId(), s.getRewardId(), reason, now);
    }
}
