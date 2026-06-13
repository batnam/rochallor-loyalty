package io.github.batnam.loyalty.core.reservation;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<PointReservation, Long> {

    Optional<PointReservation> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PointReservation r where r.reservationId = :id")
    Optional<PointReservation> findByIdForUpdate(@Param("id") Long id);

    /** Sum of points currently HELD and not yet expired — the deduction in Effective Redeemable Balance. */
    @Query("""
            select coalesce(sum(r.points), 0) from PointReservation r
            where r.memberId = :memberId and r.status = io.github.batnam.loyalty.core.reservation.ReservationStatus.HELD
              and r.heldUntil > :now
            """)
    long sumActiveHeld(@Param("memberId") Long memberId, @Param("now") Instant now);

    /** A batch of HELD reservations whose TTL has elapsed, for the sweeper. */
    @Query("""
            select r from PointReservation r
            where r.status = io.github.batnam.loyalty.core.reservation.ReservationStatus.HELD and r.heldUntil < :now
            order by r.heldUntil asc
            """)
    List<PointReservation> findExpiredHeld(@Param("now") Instant now, Limit limit);
}
