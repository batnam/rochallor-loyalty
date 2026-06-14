package io.github.batnam.loyalty.core.domain.ledger;

import java.time.Instant;

/**
 * A read-only view of a persisted Point Ledger Entry, returned by the {@code Ledger} port. Pure
 * domain value — the application/REST layer renders it without ever touching the JPA {@code @Entity}.
 */
public record LedgerEntryView(
        long entryId,
        long memberId,
        long programId,
        EntryType entryType,
        long qualifyingDelta,
        long redeemableDelta,
        String sourceRef,
        Instant createdAt
) {
}
