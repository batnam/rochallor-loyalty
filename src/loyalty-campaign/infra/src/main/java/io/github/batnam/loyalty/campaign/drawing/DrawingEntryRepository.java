package io.github.batnam.loyalty.campaign.drawing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Entry recording (L3 §3.2) is a single window-gated conditional INSERT — the {@code SELECT … WHERE
 * status=OPEN AND now() BETWEEN window} predicate eliminates the SELECT-then-INSERT race, and
 * {@code ON CONFLICT (idempotency_key) DO NOTHING} makes a Saga replay a cheap no-op. 0 rows affected
 * means either "outside window / closed" or "duplicate key"; the service disambiguates by looking the
 * key up.
 */
public interface DrawingEntryRepository extends JpaRepository<DrawingEntry, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO drawing_entry (drawing_id, member_id, saga_id, idempotency_key, weight)
            SELECT :drawingId, :memberId, :sagaId, :idempotencyKey, :weight
            WHERE EXISTS (
                SELECT 1 FROM drawing
                WHERE drawing_id = :drawingId AND status = 'OPEN'
                  AND now() BETWEEN entry_window_start AND entry_window_end)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfWindowOpen(@Param("drawingId") long drawingId,
                           @Param("memberId") long memberId,
                           @Param("sagaId") Long sagaId,
                           @Param("idempotencyKey") String idempotencyKey,
                           @Param("weight") int weight);

    Optional<DrawingEntry> findByIdempotencyKey(String idempotencyKey);

    /** Immutable entry order (by entry_id) the Winner Selection reads. */
    List<DrawingEntry> findByDrawingIdOrderByEntryIdAsc(long drawingId);

    long countByDrawingId(long drawingId);
}
