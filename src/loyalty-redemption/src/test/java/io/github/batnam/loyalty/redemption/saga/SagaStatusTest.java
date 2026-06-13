package io.github.batnam.loyalty.redemption.saga;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Saga state machine (L3 §3 / §7): {@code RESERVED -> FULFILLING -> COMMITTED|RELEASED|FAILED}.
 * Terminal states reject every further transition — the Orchestrator relies on this so a second resume
 * on a COMMITTED saga is a silent no-op (L3 §3.3 webhook idempotency). Pure logic, no I/O.
 */
class SagaStatusTest {

    @Test
    void reservedMayBeginFulfilling() {
        assertThat(SagaStatus.RESERVED.canTransitionTo(SagaStatus.FULFILLING)).isTrue();
    }

    @Test
    void reservedMayBeReleasedOrFailedBeforeFulfilment() {
        assertThat(SagaStatus.RESERVED.canTransitionTo(SagaStatus.RELEASED)).isTrue();
        assertThat(SagaStatus.RESERVED.canTransitionTo(SagaStatus.FAILED)).isTrue();
    }

    @Test
    void fulfillingMayCommitReleaseOrFail() {
        assertThat(SagaStatus.FULFILLING.canTransitionTo(SagaStatus.COMMITTED)).isTrue();
        assertThat(SagaStatus.FULFILLING.canTransitionTo(SagaStatus.RELEASED)).isTrue();
        assertThat(SagaStatus.FULFILLING.canTransitionTo(SagaStatus.FAILED)).isTrue();
    }

    @Test
    void reservedCannotJumpStraightToCommitted() {
        assertThat(SagaStatus.RESERVED.canTransitionTo(SagaStatus.COMMITTED)).isFalse();
    }

    @Test
    void terminalStatesRejectAllTransitions() {
        for (SagaStatus terminal : new SagaStatus[]{SagaStatus.COMMITTED, SagaStatus.RELEASED, SagaStatus.FAILED}) {
            for (SagaStatus to : SagaStatus.values()) {
                assertThat(terminal.canTransitionTo(to))
                        .as("%s -> %s must be rejected", terminal, to)
                        .isFalse();
            }
            assertThat(terminal.isTerminal()).isTrue();
        }
    }

    @Test
    void nonTerminalStatesAreNotTerminal() {
        assertThat(SagaStatus.RESERVED.isTerminal()).isFalse();
        assertThat(SagaStatus.FULFILLING.isTerminal()).isFalse();
    }

    @Test
    void noStatusTransitionsToItself() {
        for (SagaStatus s : SagaStatus.values()) {
            assertThat(s.canTransitionTo(s)).as("%s -> %s", s, s).isFalse();
        }
    }
}
