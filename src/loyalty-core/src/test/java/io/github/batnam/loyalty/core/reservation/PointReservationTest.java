package io.github.batnam.loyalty.core.reservation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link PointReservation} state machine: a hold starts HELD and transitions
 * exactly once to COMMITTED / RELEASED / EXPIRED. Pure POJO; no Spring / DB.
 */
class PointReservationTest {

    private static PointReservation held() {
        return PointReservation.hold(10L, 1L, 500, 99L, "idem-1", Instant.EPOCH.plusSeconds(900));
    }

    @Test
    void newHoldIsHeld() {
        PointReservation r = held();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(r.isHeld()).isTrue();
        assertThat(r.getPoints()).isEqualTo(500);
        assertThat(r.getIdempotencyKey()).isEqualTo("idem-1");
    }

    @Test
    void commitMovesToCommittedAndStoresExternalRef() {
        PointReservation r = held();
        r.commit("ext-ref-7");
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(r.isHeld()).isFalse();
        assertThat(r.getExternalRef()).isEqualTo("ext-ref-7");
    }

    @Test
    void releaseMovesToReleased() {
        PointReservation r = held();
        r.release();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(r.isHeld()).isFalse();
    }

    @Test
    void expireMovesToExpired() {
        PointReservation r = held();
        r.expire();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(r.isHeld()).isFalse();
    }
}
