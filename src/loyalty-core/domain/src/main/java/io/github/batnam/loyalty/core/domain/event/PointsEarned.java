package io.github.batnam.loyalty.core.domain.event;

import java.time.Instant;

/**
 * Recorded when the Member aggregate appends an {@code Earned} Point Ledger Entry. The outbox adapter
 * maps this to the canonical {@code loyalty.ledger.PointsEarned} event (stamping the persisted
 * {@code entryId}, which the domain does not know).
 */
public record PointsEarned(
        long memberId,
        long programId,
        long qualifyingDelta,
        long redeemableDelta,
        String sourceRef,
        Instant occurredAt
) implements DomainEvent {
    @Override
    public String eventName() {
        return "PointsEarned";
    }
}
