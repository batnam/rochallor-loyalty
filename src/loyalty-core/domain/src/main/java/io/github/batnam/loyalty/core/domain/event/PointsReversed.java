package io.github.batnam.loyalty.core.domain.event;

import java.time.Instant;

/** Recorded when the Member aggregate appends a {@code Reversed} entry (payment reversal clawback). */
public record PointsReversed(
        long memberId,
        long programId,
        long qualifyingDelta,
        long redeemableDelta,
        String sourceRef,
        Instant occurredAt
) implements DomainEvent {
    @Override
    public String eventName() {
        return "PointsReversed";
    }
}
