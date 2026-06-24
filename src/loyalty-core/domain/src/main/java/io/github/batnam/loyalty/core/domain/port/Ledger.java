package io.github.batnam.loyalty.core.domain.port;

import io.github.batnam.loyalty.core.domain.ledger.EntryType;
import io.github.batnam.loyalty.core.domain.ledger.LedgerEntryView;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for reading the append-only Point Ledger. Its purpose is the idempotency
 * contract: {@code (sourceRef, entryType)} is unique, so a caller probes here before appending and a
 * replay is a silent no-op (CONTEXT.md "Point Ledger Entry"). Returns a pure {@link LedgerEntryView}
 * so the application never depends on the JPA persistence model.
 */
public interface Ledger {

    /** The entry for {@code (sourceRef, entryType)} if one already exists, else empty. */
    Optional<LedgerEntryView> findExisting(String sourceRef, EntryType entryType);

    /**
     * Every {@code Earned} entry produced by one origin event: earning writes
     * {@code sourceRef = eventId:ruleId}, so a single payment may have fired several rules. Matches
     * {@code sourceRef = originalEventId} OR {@code sourceRef LIKE originalEventId + ":%"}. Used by the
     * payment-reversal clawback to find what to reverse.
     */
    List<LedgerEntryView> findEarnedBySourceEvent(String originalEventId);
}
