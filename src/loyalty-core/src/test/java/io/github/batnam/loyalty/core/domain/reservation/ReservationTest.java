package io.github.batnam.loyalty.core.domain.reservation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the Reservation aggregate lifecycle — no Spring, no DB. */
class ReservationTest {

    private static final Instant TTL = Instant.parse("2026-01-15T10:15:00Z");

    @Test
    void holdStartsHeldAndUnpersisted() {
        Reservation r = Reservation.hold(1L, 10L, 500, 9L, "idem-1", TTL);
        assertThat(r.isNew()).isTrue();
        assertThat(r.reservationId()).isNull();
        assertThat(r.isHeld()).isTrue();
        assertThat(r.points()).isEqualTo(500);
        assertThat(r.heldUntil()).isEqualTo(TTL);
    }

    @Test
    void commitMovesToCommittedWithExternalRef() {
        Reservation r = Reservation.rehydrate(7L, 1L, 10L, 500, 9L, "idem-1",
                ReservationStatus.HELD, null, TTL);
        r.commit("disbursement-xyz");
        assertThat(r.isCommitted()).isTrue();
        assertThat(r.isHeld()).isFalse();
        assertThat(r.externalRef()).isEqualTo("disbursement-xyz");
    }

    @Test
    void releaseMovesToReleased() {
        Reservation r = Reservation.rehydrate(7L, 1L, 10L, 500, 9L, "idem-1",
                ReservationStatus.HELD, null, TTL);
        r.release();
        assertThat(r.isReleased()).isTrue();
        assertThat(r.isHeld()).isFalse();
    }
}
