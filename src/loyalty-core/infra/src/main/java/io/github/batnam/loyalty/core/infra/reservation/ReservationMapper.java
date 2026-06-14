package io.github.batnam.loyalty.core.infra.reservation;

import io.github.batnam.loyalty.core.domain.reservation.Reservation;
import io.github.batnam.loyalty.core.domain.reservation.ReservationStatus;
import io.github.batnam.loyalty.core.reservation.PointReservation;

/** Maps the {@code point_reservation} {@code @Entity} to the pure domain {@link Reservation}. */
final class ReservationMapper {

    private ReservationMapper() {
    }

    static Reservation toDomain(PointReservation e) {
        return Reservation.rehydrate(
                e.getReservationId(),
                e.getMemberId(),
                e.getProgramId(),
                e.getPoints(),
                e.getRewardId(),
                e.getIdempotencyKey(),
                ReservationStatus.valueOf(e.getStatus().name()),
                e.getExternalRef(),
                e.getHeldUntil());
    }
}
