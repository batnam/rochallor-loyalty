package io.github.batnam.loyalty.core.domain.member;

import io.github.batnam.loyalty.core.domain.member.TcsGracePolicy.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure {@link TcsGracePolicy}: OK when caught up, IN_GRACE within 30 days of the
 * version becoming effective, GRACE_EXPIRED once the window elapses, and the boundary / null cases.
 */
class TcsGracePolicyTest {

    private static final Instant EFFECTIVE = Instant.parse("2026-01-01T00:00:00Z");
    private static final int GRACE_DAYS = 30;

    private static Status eval(Integer accepted, int current, Instant now) {
        return TcsGracePolicy.evaluate(accepted, current, EFFECTIVE, now, GRACE_DAYS);
    }

    @Test
    void okWhenAcceptedMeetsCurrent() {
        // even long after the effective instant, an up-to-date Member is OK
        assertThat(eval(2, 2, EFFECTIVE.plus(100, ChronoUnit.DAYS))).isEqualTo(Status.OK);
        assertThat(eval(3, 2, EFFECTIVE.plus(100, ChronoUnit.DAYS))).isEqualTo(Status.OK);
    }

    @Test
    void inGraceWhenBehindAndWithinWindow() {
        assertThat(eval(1, 2, EFFECTIVE)).isEqualTo(Status.IN_GRACE);                       // day 0
        assertThat(eval(1, 2, EFFECTIVE.plus(29, ChronoUnit.DAYS))).isEqualTo(Status.IN_GRACE);
    }

    @Test
    void graceExpiredAtAndAfterBoundary() {
        // boundary is inclusive of expiry: now == effective + 30d is GRACE_EXPIRED
        assertThat(eval(1, 2, EFFECTIVE.plus(30, ChronoUnit.DAYS))).isEqualTo(Status.GRACE_EXPIRED);
        assertThat(eval(1, 2, EFFECTIVE.plus(31, ChronoUnit.DAYS))).isEqualTo(Status.GRACE_EXPIRED);
    }

    @Test
    void justBeforeBoundaryStillInGrace() {
        assertThat(eval(1, 2, EFFECTIVE.plus(30, ChronoUnit.DAYS).minusSeconds(1)))
                .isEqualTo(Status.IN_GRACE);
    }

    @Test
    void nullAcceptedIsTreatedAsBehind() {
        assertThat(eval(null, 1, EFFECTIVE.plus(1, ChronoUnit.DAYS))).isEqualTo(Status.IN_GRACE);
        assertThat(eval(null, 1, EFFECTIVE.plus(40, ChronoUnit.DAYS))).isEqualTo(Status.GRACE_EXPIRED);
    }

    @Test
    void redeemBlockedInBothGraceStatesButNotWhenOk() {
        assertThat(TcsGracePolicy.redeemBlocked(2, 2, EFFECTIVE, EFFECTIVE, GRACE_DAYS)).isFalse();
        assertThat(TcsGracePolicy.redeemBlocked(1, 2, EFFECTIVE, EFFECTIVE, GRACE_DAYS)).isTrue();   // grace
        assertThat(TcsGracePolicy.redeemBlocked(1, 2, EFFECTIVE,
                EFFECTIVE.plus(40, ChronoUnit.DAYS), GRACE_DAYS)).isTrue();                          // expired
    }
}
