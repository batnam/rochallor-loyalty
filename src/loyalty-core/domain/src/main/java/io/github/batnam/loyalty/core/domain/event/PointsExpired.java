package io.github.batnam.loyalty.core.domain.event;

import java.time.Instant;

/** Recorded when the Member aggregate appends an {@code Expired} entry (cohort expiry). */
public record PointsExpired(
        long memberId,
        long programId,
        long qualifyingDelta,
        long redeemableDelta,
        String sourceRef,
        Instant occurredAt
) implements DomainEvent {
    @Override
    public String eventName() {
        return "PointsExpired";
    }
}
