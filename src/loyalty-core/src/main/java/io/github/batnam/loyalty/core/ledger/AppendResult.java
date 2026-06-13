package io.github.batnam.loyalty.core.ledger;

import io.github.batnam.loyalty.core.domain.ledger.LedgerEntryView;

/**
 * Outcome of a Ledger append: a pure {@link LedgerEntryView} of the entry plus whether it was an
 * idempotent replay of an existing one. Carries the domain view (not the JPA entity) so callers and
 * the REST layer stay off the persistence model (ADR-0001).
 */
public record AppendResult(LedgerEntryView entry, boolean replayed) {
}
