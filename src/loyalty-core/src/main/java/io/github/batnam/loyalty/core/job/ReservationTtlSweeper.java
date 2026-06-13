package io.github.batnam.loyalty.core.job;

import io.github.batnam.loyalty.core.domain.port.Reservations;
import io.github.batnam.loyalty.core.domain.reservation.Reservation;
import io.github.batnam.loyalty.core.error.CoreException;
import io.github.batnam.loyalty.core.reservation.ReservationManager;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Reservation TTL Sweeper (L3 §3.3, component 11). Every 60s, releases HELD reservations whose
 * {@code held_until} has elapsed, through the {@code Reservations} port — it never takes the Member
 * lock (ADR-0001). ShedLock-guarded so exactly one pod runs each tick. Batched (LIMIT 500) to keep
 * transactions bounded; runs again next tick if more remain.
 */
@Component
public class ReservationTtlSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationTtlSweeper.class);
    private static final int BATCH = 500;

    private final Reservations reservations;
    private final ReservationManager manager;

    public ReservationTtlSweeper(Reservations reservations, ReservationManager manager) {
        this.reservations = reservations;
        this.manager = manager;
    }

    @Scheduled(fixedDelayString = "PT60S")
    @SchedulerLock(name = "reservationTtlSweeper", lockAtMostFor = "PT5M")
    public void sweep() {
        List<Reservation> expired = reservations.findExpiredHeld(Instant.now(), BATCH);
        int released = 0;
        for (Reservation r : expired) {
            try {
                manager.release(r.reservationId(), "TTL_EXPIRED");
                released++;
            } catch (CoreException raced) {
                // Committed/released between the scan and the lock — fine, skip it.
                log.debug("skip reservationId={} during sweep: {}", r.reservationId(), raced.getMessage());
            }
        }
        if (released > 0) log.info("TTL sweeper released {} expired reservation(s)", released);
    }
}
