package io.github.batnam.loyalty.redemption.saga;

import java.util.Set;

/**
 * The two-phase redemption Saga state machine (L3 §3 / §7).
 *
 * <pre>
 *   RESERVED ──▶ FULFILLING ──▶ COMMITTED   (happy path: reserve → fulfil → commit)
 *      │             │      └──▶ RELEASED    (reservation TTL expired during async wait)
 *      │             └─────────▶ FAILED      (adapter / partner failure → release)
 *      ├──▶ RELEASED                         (reservation released before fulfilment, e.g. TTL)
 *      └──▶ FAILED                           (gave up before fulfilment)
 * </pre>
 *
 * {@code COMMITTED}, {@code RELEASED} and {@code FAILED} are terminal — they reject every further
 * transition, which is what makes a second resume on an already-finished saga a safe no-op.
 */
public enum SagaStatus {
    RESERVED,
    FULFILLING,
    COMMITTED,
    RELEASED,
    FAILED;

    private static final Set<SagaStatus> TERMINAL = Set.of(COMMITTED, RELEASED, FAILED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** @return true iff this -> {@code target} is a legal Saga transition (never reflexive). */
    public boolean canTransitionTo(SagaStatus target) {
        return switch (this) {
            case RESERVED -> target == FULFILLING || target == RELEASED || target == FAILED;
            case FULFILLING -> target == COMMITTED || target == RELEASED || target == FAILED;
            case COMMITTED, RELEASED, FAILED -> false;
        };
    }
}
