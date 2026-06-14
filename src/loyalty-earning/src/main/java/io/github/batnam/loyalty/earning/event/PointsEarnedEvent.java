package io.github.batnam.loyalty.earning.event;

import java.time.Instant;
import java.util.List;

/**
 * Canonical {@code loyalty.earning.points_earned.v1} event (asyncapi/loyalty-earning.yaml), emitted
 * once per Earn Event after the Earned Ledger entries are written. Self-describing envelope keyed
 * downstream by {@code memberId}; {@code eventId} derived from {@code sourceEventId} so it is stable
 * across at-least-once outbox replays.
 */
public record PointsEarnedEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        long programId,
        int schemaVersion,
        long memberId,
        long totalQualifyingDelta,
        long totalRedeemableDelta,
        List<Long> entryIds,
        String sourceEventId
) {
    public static PointsEarnedEvent of(String sourceEventId, long programId, long memberId,
                                       long totalQualifyingDelta, long totalRedeemableDelta,
                                       List<Long> entryIds, Instant occurredAt) {
        return new PointsEarnedEvent(
                "earning-" + sourceEventId, "PointsEarned", occurredAt, programId, 1, memberId,
                totalQualifyingDelta, totalRedeemableDelta, entryIds, sourceEventId);
    }
}
