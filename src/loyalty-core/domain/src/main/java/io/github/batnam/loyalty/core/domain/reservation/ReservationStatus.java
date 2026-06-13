package io.github.batnam.loyalty.core.domain.reservation;

/** Two-phase redemption hold lifecycle (CONTEXT.md "Reservation"). Pure domain copy. */
public enum ReservationStatus {
    HELD, COMMITTED, RELEASED, EXPIRED
}
