package io.github.batnam.loyalty.core.reservation;

/** Two-phase redemption hold lifecycle (CONTEXT.md "Reservation"). */
public enum ReservationStatus {
    HELD, COMMITTED, RELEASED, EXPIRED
}
