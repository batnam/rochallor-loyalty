package io.github.batnam.loyalty.campaign.drawing;

import java.util.Set;

/**
 * Drawing lifecycle (L3 §3.3).
 *
 * <pre>
 *   OPEN ──▶ CLOSED   (winners selected; K winner_record rows written)
 *      └───▶ VOID     (zero entries at draw time)
 * </pre>
 *
 * {@code CLOSED} and {@code VOID} are terminal — they reject every further transition, which makes a
 * duplicated scheduler fire on an already-drawn Drawing a safe no-op (belt to the ShedLock + FOR UPDATE
 * braces).
 */
public enum DrawingStatus {
    OPEN,
    CLOSED,
    VOID;

    private static final Set<DrawingStatus> TERMINAL = Set.of(CLOSED, VOID);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** @return true iff this -> {@code target} is a legal Drawing transition (never reflexive). */
    public boolean canTransitionTo(DrawingStatus target) {
        return switch (this) {
            case OPEN -> target == CLOSED || target == VOID;
            case CLOSED, VOID -> false;
        };
    }
}
