package io.github.batnam.loyalty.core.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Append-only access to {@code point_ledger}. {@code save()} INSERTs only — the {@code @Immutable}
 * entity + DB trigger guarantee no UPDATE/DELETE. The Ledger Service is the single writer (P5).
 */
public interface LedgerRepository extends JpaRepository<PointLedgerEntry, Long> {

    /** Idempotency probe: {@code (sourceRef, entryType)} is unique. */
    Optional<PointLedgerEntry> findBySourceRefAndEntryType(String sourceRef, EntryType entryType);
}
