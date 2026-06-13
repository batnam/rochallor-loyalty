package io.github.batnam.loyalty.campaign.drawing;

import io.github.batnam.loyalty.campaign.select.SelectionStrategy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A sweepstakes Drawing under a Campaign (L3 §5 {@code drawing}). OPEN → CLOSED (winners selected) | VOID
 * (zero entries). {@code drawAt} drives the in-pod Drawing Scheduler. The {@code close}/{@code markVoid}
 * transitions go through {@link DrawingStatus#canTransitionTo}, so a duplicated scheduler fire on an
 * already-drawn Drawing throws rather than re-selecting.
 */
@Entity
@Table(name = "drawing")
public class Drawing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "drawing_id")
    private Long drawingId;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "program_id", nullable = false, updatable = false)
    private Long programId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prize", nullable = false)
    private String prize = "{}";

    @Column(name = "entry_window_start", nullable = false)
    private Instant entryWindowStart;

    @Column(name = "entry_window_end", nullable = false)
    private Instant entryWindowEnd;

    @Column(name = "draw_at", nullable = false)
    private Instant drawAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "selection_strategy", nullable = false, updatable = false)
    private SelectionStrategy selectionStrategy = SelectionStrategy.SEEDED_RNG;

    @Column(name = "winners_count", nullable = false)
    private int winnersCount = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DrawingStatus status = DrawingStatus.OPEN;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Drawing() {
    }

    public static Drawing open(Long campaignId, Long programId, String prize, Instant entryWindowStart,
                               Instant entryWindowEnd, Instant drawAt, SelectionStrategy selectionStrategy,
                               int winnersCount) {
        Drawing d = new Drawing();
        d.campaignId = campaignId;
        d.programId = programId;
        d.prize = prize == null ? "{}" : prize;
        d.entryWindowStart = entryWindowStart;
        d.entryWindowEnd = entryWindowEnd;
        d.drawAt = drawAt;
        d.selectionStrategy = selectionStrategy == null ? SelectionStrategy.SEEDED_RNG : selectionStrategy;
        d.winnersCount = Math.max(1, winnersCount);
        d.status = DrawingStatus.OPEN;
        return d;
    }

    private void transitionTo(DrawingStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("illegal drawing transition " + status + " -> " + target
                    + " (drawing " + drawingId + ")");
        }
        this.status = target;
    }

    public void close() { transitionTo(DrawingStatus.CLOSED); }
    public void markVoid() { transitionTo(DrawingStatus.VOID); }

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }

    public Long getDrawingId() { return drawingId; }
    public Long getCampaignId() { return campaignId; }
    public Long getProgramId() { return programId; }
    public String getPrize() { return prize; }
    public Instant getEntryWindowStart() { return entryWindowStart; }
    public Instant getEntryWindowEnd() { return entryWindowEnd; }
    public Instant getDrawAt() { return drawAt; }
    public SelectionStrategy getSelectionStrategy() { return selectionStrategy; }
    public int getWinnersCount() { return winnersCount; }
    public DrawingStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
