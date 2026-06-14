package io.github.batnam.loyalty.earning.engine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per processed EarnEvent {@code eventId} (L3 §5 {@code idempotency_key}) — the Rule Engine's
 * replay gate. Written last, in the same transaction as the rule work, so a replay short-circuits
 * before touching the rule cache or DSL. Cheaper than relying solely on core's {@code (sourceRef,
 * entryType)} uniqueness because it stops before any rule work.
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyKey {

    @Id
    @Column(name = "event_id", updatable = false)
    private String eventId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "program_id", nullable = false, updatable = false)
    private Long programId;

    @Column(name = "processed_at", nullable = false, updatable = false, insertable = false)
    private Instant processedAt;

    protected IdempotencyKey() {
    }

    public static IdempotencyKey of(String eventId, Long memberId, Long programId) {
        IdempotencyKey k = new IdempotencyKey();
        k.eventId = eventId;
        k.memberId = memberId;
        k.programId = programId;
        return k;
    }

    public String getEventId() { return eventId; }
    public Long getMemberId() { return memberId; }
    public Long getProgramId() { return programId; }
    public Instant getProcessedAt() { return processedAt; }
}
