package io.github.batnam.loyalty.earning.replay;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface EarnEventLogRepository extends JpaRepository<EarnEventLog, String> {

    /**
     * Persist a consumed event, ignoring replays (PK conflict on event_id). Native upsert avoids a
     * read-before-write and keeps the consumer idempotent on the replay store.
     */
    @Modifying
    @Query(value = """
            INSERT INTO earn_event_log (event_id, source, customer_id, payload, occurred_at)
            VALUES (:eventId, :source, :customerId, CAST(:payload AS jsonb), :occurredAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    void insertIgnore(@Param("eventId") String eventId, @Param("source") String source,
                      @Param("customerId") long customerId, @Param("payload") String payload,
                      @Param("occurredAt") Instant occurredAt);

    /** Dry-run window: events for a source within [from, to], oldest first. */
    List<EarnEventLog> findBySourceAndOccurredAtBetweenOrderByOccurredAtAsc(
            String source, Instant from, Instant to);
}
