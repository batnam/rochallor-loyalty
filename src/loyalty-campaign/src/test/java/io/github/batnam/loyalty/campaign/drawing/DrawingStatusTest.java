package io.github.batnam.loyalty.campaign.drawing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Drawing lifecycle (L3 §3.3): OPEN → CLOSED (winners selected) | VOID (zero entries). CLOSED and
 * VOID are terminal so a duplicated scheduler fire on an already-drawn Drawing is a safe no-op. Pure logic.
 */
class DrawingStatusTest {

    @Test
    void openMayCloseOrVoid() {
        assertThat(DrawingStatus.OPEN.canTransitionTo(DrawingStatus.CLOSED)).isTrue();
        assertThat(DrawingStatus.OPEN.canTransitionTo(DrawingStatus.VOID)).isTrue();
    }

    @Test
    void terminalStatesRejectAllTransitions() {
        for (DrawingStatus terminal : new DrawingStatus[]{DrawingStatus.CLOSED, DrawingStatus.VOID}) {
            for (DrawingStatus to : DrawingStatus.values()) {
                assertThat(terminal.canTransitionTo(to))
                        .as("%s -> %s must be rejected", terminal, to).isFalse();
            }
            assertThat(terminal.isTerminal()).isTrue();
        }
    }

    @Test
    void openIsNotTerminal() {
        assertThat(DrawingStatus.OPEN.isTerminal()).isFalse();
    }

    @Test
    void noStatusTransitionsToItself() {
        for (DrawingStatus s : DrawingStatus.values()) {
            assertThat(s.canTransitionTo(s)).as("%s -> %s", s, s).isFalse();
        }
    }
}
