package io.github.batnam.loyalty.core.cohort;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the FIFO consumption logic of {@link CohortProjection#consumeFifo}. The repository is
 * mocked and returns cohorts already ordered earned-at-ascending (the repo's contract; DB ordering is
 * covered by the integration test) — these tests pin the projection's own behaviour: oldest-first,
 * skip-expired, and graceful under-consumption when cohorts run dry (negative balance is allowed).
 */
class CohortProjectionTest {

    private final CohortRepository repo = mock(CohortRepository.class);
    private final CohortProjection projection = new CohortProjection(repo);

    private static final Instant FUTURE = Instant.now().plusSeconds(86_400);
    private static final Instant PAST = Instant.now().minusSeconds(86_400);

    private static PointCohort cohort(long entryId, long original, Instant earnedAt, Instant expiresAt) {
        return PointCohort.open(entryId, 10L, 1L, original, earnedAt, expiresAt);
    }

    @Test
    void consumesOldestCohortFirstAcrossMultipleCohorts() {
        PointCohort older = cohort(1, 300, Instant.EPOCH, FUTURE);
        PointCohort newer = cohort(2, 300, Instant.EPOCH.plusSeconds(10), FUTURE);
        when(repo.findByMemberIdAndProgramIdOrderByEarnedAtAsc(anyLong(), anyLong()))
                .thenReturn(List.of(older, newer));

        List<PointCohort> touched = projection.consumeFifo(10L, 1L, 400);

        assertThat(older.getConsumedAmount()).isEqualTo(300);   // older drained fully first
        assertThat(newer.getConsumedAmount()).isEqualTo(100);   // remainder spills to newer
        assertThat(touched).containsExactly(older, newer);
    }

    @Test
    void stopsAtFirstCohortWhenItCoversTheWholeAmount() {
        PointCohort older = cohort(1, 1_000, Instant.EPOCH, FUTURE);
        PointCohort newer = cohort(2, 1_000, Instant.EPOCH.plusSeconds(10), FUTURE);
        when(repo.findByMemberIdAndProgramIdOrderByEarnedAtAsc(anyLong(), anyLong()))
                .thenReturn(List.of(older, newer));

        List<PointCohort> touched = projection.consumeFifo(10L, 1L, 400);

        assertThat(older.getConsumedAmount()).isEqualTo(400);
        assertThat(newer.getConsumedAmount()).isZero();
        assertThat(touched).containsExactly(older);
    }

    @Test
    void skipsExpiredCohortsAndConsumesFromLiveOnes() {
        PointCohort expired = cohort(1, 500, Instant.EPOCH, PAST);
        PointCohort live = cohort(2, 500, Instant.EPOCH.plusSeconds(10), FUTURE);
        when(repo.findByMemberIdAndProgramIdOrderByEarnedAtAsc(anyLong(), anyLong()))
                .thenReturn(List.of(expired, live));

        List<PointCohort> touched = projection.consumeFifo(10L, 1L, 200);

        assertThat(expired.getConsumedAmount()).isZero();       // expired cohort is not consumable
        assertThat(live.getConsumedAmount()).isEqualTo(200);
        assertThat(touched).containsExactly(live);
    }

    @Test
    void underConsumesWhenCohortsAreExhausted() {
        PointCohort only = cohort(1, 100, Instant.EPOCH, FUTURE);
        when(repo.findByMemberIdAndProgramIdOrderByEarnedAtAsc(anyLong(), anyLong()))
                .thenReturn(List.of(only));

        // Ask for more than exists — the Ledger remains source of truth, balance can go negative,
        // and only what cohorts held is tracked as consumed.
        List<PointCohort> touched = projection.consumeFifo(10L, 1L, 250);

        assertThat(only.getConsumedAmount()).isEqualTo(100);
        assertThat(touched).containsExactly(only);
    }
}
