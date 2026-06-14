package io.github.batnam.loyalty.core.event;

import io.github.batnam.loyalty.core.ledger.EntryType;

import java.time.Instant;

/**
 * Canonical {@code loyalty.ledger.*} event envelope produced by core via the Outbox Relay
 * (e.g. {@code PointsEarned}, {@code PointsRedeemed}, {@code PointsExpired}). Shape mirrors the
 * bridge's canonical events: a self-describing envelope keyed downstream by {@code memberId}.
 * {@code eventId} is derived from the immutable ledger entry so it is stable across replays.
 */
public record LedgerEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        Integer schemaVersion,
        Long memberId,
        Long programId,
        Long entryId,
        EntryType ledgerEntryType,
        long qualifyingDelta,
        long redeemableDelta,
        String sourceRef
) {
    public static LedgerEvent of(String eventType, Long memberId, Long programId, Long entryId,
                                 EntryType ledgerEntryType, long qualifyingDelta, long redeemableDelta,
                                 String sourceRef, Instant occurredAt) {
        return new LedgerEvent("ledger-" + entryId, eventType, occurredAt, 1, memberId, programId,
                entryId, ledgerEntryType, qualifyingDelta, redeemableDelta, sourceRef);
    }
}
