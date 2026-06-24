package io.github.batnam.loyalty.core.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Append-only access to {@code point_ledger}. {@code save()} INSERTs only — the {@code @Immutable}
 * entity + DB trigger guarantee no UPDATE/DELETE. The Ledger Service is the single writer (P5).
 */
public interface LedgerRepository extends JpaRepository<PointLedgerEntry, Long> {

    /** Idempotency probe: {@code (sourceRef, entryType)} is unique. */
    Optional<PointLedgerEntry> findBySourceRefAndEntryType(String sourceRef, EntryType entryType);

    /**
     * All {@code Earned} entries originating from one event id. Earning writes
     * {@code sourceRef = eventId:ruleId}, so match the bare id or the {@code id:} prefix.
     */
    @Query("select e from PointLedgerEntry e where e.entryType = io.github.batnam.loyalty.core.ledger.EntryType.Earned "
            + "and (e.sourceRef = :ev or e.sourceRef like :prefix)")
    List<PointLedgerEntry> findEarnedBySourceEvent(@Param("ev") String ev, @Param("prefix") String prefix);

    /**
     * Windowed Qualifying Balance: {@code SUM(qualifyingDelta)} over this Member's Ledger for the
     * Program, restricted to entries at/after {@code since} (CONTEXT.md "Qualifying Metric" —
     * {@code ROLLING_12_MONTHS}/{@code CALENDAR_YEAR}). Falls back to {@code createdAt} when
     * {@code occurredAt} is null. {@code COALESCE(SUM(...),0)} so an empty window yields 0.
     */
    @Query("select coalesce(sum(e.qualifyingDelta), 0) from PointLedgerEntry e "
            + "where e.memberId = :memberId and e.programId = :programId "
            + "and coalesce(e.occurredAt, e.createdAt) >= :since")
    long sumQualifyingSince(@Param("memberId") long memberId, @Param("programId") long programId,
                            @Param("since") Instant since);
}
