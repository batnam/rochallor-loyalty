package io.github.batnam.loyalty.core.domain.port;

import io.github.batnam.loyalty.core.domain.reservation.Reservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Domain port for the Reservation aggregate (ADR-0001). Its lock is deliberately separate from the
 * Member's: {@link #findForUpdate} locks only the {@code point_reservation} row, and
 * {@link #sumActiveHeld} is an O(1) aggregate — so commit/release/sweep never contend on the Member
 * write lock. Two adapters satisfy it: a JPA-backed one in production and an in-memory fake in tests.
 */
public interface Reservations {

    /** Insert a new HELD reservation, or persist a transition on an existing one. Returns the saved aggregate. */
    Reservation save(Reservation reservation);

    /** The reservation created under {@code idempotencyKey}, if any (the reserve idempotency gate). */
    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    /** Load a reservation under a pessimistic row lock for a commit/release transition. */
    Optional<Reservation> findForUpdate(long reservationId);

    /** Sum of points currently HELD and not yet expired — the deduction in Effective Redeemable Balance. */
    long sumActiveHeld(long memberId, Instant now);

    /** Up to {@code limit} HELD reservations whose TTL has elapsed (oldest first), for the sweeper. */
    List<Reservation> findExpiredHeld(Instant now, int limit);
}
