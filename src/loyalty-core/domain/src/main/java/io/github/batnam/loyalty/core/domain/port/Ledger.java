package io.github.batnam.loyalty.core.domain.port;

import io.github.batnam.loyalty.core.domain.ledger.EntryType;
import io.github.batnam.loyalty.core.domain.ledger.LedgerEntryView;

import java.util.Optional;

/**
 * Domain port for reading the append-only Point Ledger (ADR-0001). Its purpose is the idempotency
 * contract: {@code (sourceRef, entryType)} is unique, so a caller probes here before appending and a
 * replay is a silent no-op (CONTEXT.md "Point Ledger Entry"). Returns a pure {@link LedgerEntryView}
 * so the application never depends on the JPA persistence model.
 */
public interface Ledger {

    /** The entry for {@code (sourceRef, entryType)} if one already exists, else empty. */
    Optional<LedgerEntryView> findExisting(String sourceRef, EntryType entryType);
}
