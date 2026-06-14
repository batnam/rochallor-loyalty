package io.github.batnam.loyalty.core.domain.ledger;

import java.time.Instant;

/**
 * A Point Ledger Entry the {@link io.github.batnam.loyalty.core.domain.member.Member} aggregate has
 * decided to append <i>in the current transaction</i> but which has not yet been persisted (so it
 * carries no {@code entryId}). The {@code :infra} adapter turns this into a persisted, immutable row.
 *
 * <p>The aggregate is the only producer of these — preserving the single-writer (P5) and Ledger
 * immutability invariants. {@code sourceRef} carries the idempotency key (CONTEXT.md "Point Ledger Entry").
 */
public record NewLedgerEntry(
        EntryType type,
        long qualifyingDelta,
        long redeemableDelta,
        String sourceRef,
        String reason,
        String earnSourceCode,
        String currency,
        Long approvalRequestId,
        Instant occurredAt
) {
}
