package io.github.batnam.loyalty.core.infra.reservation;

import io.github.batnam.loyalty.core.domain.port.Reservations;
import io.github.batnam.loyalty.core.domain.reservation.Reservation;
import io.github.batnam.loyalty.core.reservation.PointReservation;
import io.github.batnam.loyalty.core.reservation.ReservationRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA-backed adapter for the {@link Reservations} port. New reservations are inserted;
 * transitions are applied to the row already locked by {@link #findForUpdate} (same transaction →
 * Hibernate L1 cache returns the managed, locked instance), so dirty-checking flushes the status.
 */
@Component
public class JpaReservations implements Reservations {

    private final ReservationRepository reservations;

    public JpaReservations(ReservationRepository reservations) {
        this.reservations = reservations;
    }

    @Override
    public Reservation save(Reservation r) {
        if (r.isNew()) {
            PointReservation saved = reservations.save(PointReservation.hold(
                    r.memberId(), r.programId(), r.points(), r.rewardId(), r.idempotencyKey(), r.heldUntil()));
            return ReservationMapper.toDomain(saved);
        }
        PointReservation entity = reservations.findById(r.reservationId())
                .orElseThrow(() -> new IllegalStateException(
                        "reservation vanished mid-transaction id=" + r.reservationId()));
        switch (r.status()) {
            case COMMITTED -> entity.commit(r.externalRef());
            case RELEASED -> entity.release();
            case EXPIRED -> entity.expire();
            case HELD -> { /* no transition */ }
        }
        return ReservationMapper.toDomain(entity);
    }

    @Override
    public Optional<Reservation> findByIdempotencyKey(String idempotencyKey) {
        return reservations.findByIdempotencyKey(idempotencyKey).map(ReservationMapper::toDomain);
    }

    @Override
    public Optional<Reservation> findForUpdate(long reservationId) {
        return reservations.findByIdForUpdate(reservationId).map(ReservationMapper::toDomain);
    }

    @Override
    public long sumActiveHeld(long memberId, Instant now) {
        return reservations.sumActiveHeld(memberId, now);
    }

    @Override
    public List<Reservation> findExpiredHeld(Instant now, int limit) {
        return reservations.findExpiredHeld(now, Limit.of(limit)).stream()
                .map(ReservationMapper::toDomain)
                .toList();
    }
}
