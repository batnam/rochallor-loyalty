package io.github.batnam.loyalty.core.domain.event;

import java.time.Instant;

/** Recorded when the Member aggregate appends an {@code Adjusted} entry (Maker-Checker via BEP). */
public record PointsAdjusted(
        long memberId,
        long programId,
        long qualifyingDelta,
        long redeemableDelta,
        String sourceRef,
        Instant occurredAt
) implements DomainEvent {
    @Override
    public String eventName() {
        return "PointsAdjusted";
    }
}
