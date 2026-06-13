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
 * One immutable winner row (L3 §5 {@code winner_record}; K per drawing). Audit-replayable: the row plus the
 * HMAC secret and the frozen entry order/weights reproduce a SEEDED_RNG/WEIGHTED selection ({@code seedHex}
 * is NULL for FIRST_N). Insert-only (DB trigger + {@code @Immutable}); {@code UNIQUE(drawing_id,
 * winner_index)} blocks duplicate selection of a slot.
 */
@Entity
@Immutable
@Table(name = "winner_record")
public class WinnerRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "winner_id")
    private Long winnerId;

    @Column(name = "drawing_id", nullable = false)
    private Long drawingId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "winner_index", nullable = false)
    private int winnerIndex;

    @Column(name = "seed_hex")
    private String seedHex;

    @Column(name = "drawn_at", nullable = false, insertable = false, updatable = false)
    private Instant drawnAt;

    protected WinnerRecord() {
    }

    public static WinnerRecord of(Long drawingId, Long memberId, int winnerIndex, String seedHex) {
        WinnerRecord w = new WinnerRecord();
        w.drawingId = drawingId;
        w.memberId = memberId;
        w.winnerIndex = winnerIndex;
        w.seedHex = seedHex;
        return w;
    }

    public Long getWinnerId() { return winnerId; }
    public Long getDrawingId() { return drawingId; }
    public Long getMemberId() { return memberId; }
    public int getWinnerIndex() { return winnerIndex; }
    public String getSeedHex() { return seedHex; }
    public Instant getDrawnAt() { return drawnAt; }
}
