package io.github.batnam.loyalty.core.domain.event;

import java.time.Instant;

/** Recorded when the Member aggregate appends a {@code Redeemed} entry (commit of a reservation). */
public record PointsRedeemed(
        long memberId,
        long programId,
        long qualifyingDelta,
        long redeemableDelta,
        String sourceRef,
        Instant occurredAt
) implements DomainEvent {
    @Override
    public String eventName() {
        return "PointsRedeemed";
    }
}
