package io.github.batnam.loyalty.core.api.dto;

import io.github.batnam.loyalty.core.domain.ledger.LedgerEntryView;
import io.github.batnam.loyalty.core.ledger.EntryType;

import java.time.Instant;

/** {@code LedgerEntry} response (loyalty-core.yaml). Built from the domain {@link LedgerEntryView}. */
public record LedgerEntryResponse(
        Long entryId,
        Long memberId,
        Long programId,
        EntryType entryType,
        long qualifyingDelta,
        long redeemableDelta,
        String sourceRef,
        Instant createdAt
) {
    public static LedgerEntryResponse from(LedgerEntryView v) {
        return new LedgerEntryResponse(v.entryId(), v.memberId(), v.programId(),
                EntryType.valueOf(v.entryType().name()), v.qualifyingDelta(), v.redeemableDelta(),
                v.sourceRef(), v.createdAt());
    }
}
