package io.github.batnam.loyalty.core.cohort;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the FIFO consumption arithmetic of a single {@link PointCohort}
 * (remaining = original − consumed − expired). Pure POJO; no Spring / DB.
 */
class PointCohortTest {

    private static PointCohort cohort(long original) {
        return PointCohort.open(1L, 10L, 1L, original, Instant.EPOCH, Instant.EPOCH.plusSeconds(86_400));
    }

    @Test
    void freshCohortHasFullRemainingAndNothingConsumedOrExpired() {
        PointCohort c = cohort(1_000);
        assertThat(c.remaining()).isEqualTo(1_000);
        assertThat(c.getConsumedAmount()).isZero();
        assertThat(c.getExpiredAmount()).isZero();
        assertThat(c.getOriginalAmount()).isEqualTo(1_000);
    }

    @Test
    void consumeReducesRemainingAndAccumulates() {
        PointCohort c = cohort(1_000);
        c.consume(300);
        c.consume(200);
        assertThat(c.getConsumedAmount()).isEqualTo(500);
        assertThat(c.remaining()).isEqualTo(500);
    }

    @Test
    void expireReducesRemainingIndependentlyOfConsumption() {
        PointCohort c = cohort(1_000);
        c.consume(400);
        c.expire(600);
        assertThat(c.getExpiredAmount()).isEqualTo(600);
        assertThat(c.remaining()).isZero();
    }
}
