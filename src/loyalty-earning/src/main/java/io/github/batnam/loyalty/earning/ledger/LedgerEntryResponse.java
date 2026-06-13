package io.github.batnam.loyalty.earning.ledger;

import java.time.Instant;

/**
 * Response of {@code POST /ledger/earn} (loyalty-core.yaml {@code LedgerEntry}). Earning only needs
 * the {@code entryId} (carried into the PointsEarned event), but the full shape is kept for clarity.
 * Unknown fields are ignored (Jackson FAIL_ON_UNKNOWN disabled).
 */
public record LedgerEntryResponse(
        Long entryId,
        Long memberId,
        Long programId,
        String entryType,
        long qualifyingDelta,
        long redeemableDelta,
        String sourceRef,
        Instant createdAt
) {
}
