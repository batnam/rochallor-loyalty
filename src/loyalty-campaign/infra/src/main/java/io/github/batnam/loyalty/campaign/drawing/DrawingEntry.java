package io.github.batnam.loyalty.campaign.drawing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * One Member entry into a Drawing (L3 §5 {@code drawing_entry}). Written by the Entry Service via a single
 * window-gated conditional INSERT (no SELECT-then-INSERT race); {@code idempotencyKey} is UNIQUE so a
 * redemption-Saga replay is a no-op. {@code weight} is frozen at entry time for WEIGHTED selection.
 * Immutable once written.
 */
@Entity
@Immutable
@Table(name = "drawing_entry")
public class DrawingEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long entryId;

    @Column(name = "drawing_id", nullable = false)
    private Long drawingId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "saga_id")
    private Long sagaId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "weight", nullable = false)
    private int weight;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected DrawingEntry() {
    }

    public Long getEntryId() { return entryId; }
    public Long getDrawingId() { return drawingId; }
    public Long getMemberId() { return memberId; }
    public Long getSagaId() { return sagaId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getWeight() { return weight; }
    public Instant getCreatedAt() { return createdAt; }
}
