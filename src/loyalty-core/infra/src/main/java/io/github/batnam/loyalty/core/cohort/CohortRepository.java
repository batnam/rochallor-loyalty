package io.github.batnam.loyalty.core.cohort;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface CohortRepository extends JpaRepository<PointCohort, Long> {

    /** Oldest-first cohorts for a Member that still have points to consume (FIFO order). */
    List<PointCohort> findByMemberIdAndProgramIdOrderByEarnedAtAsc(Long memberId, Long programId);

    /** Expired-but-unconsumed cohorts across a Program, for the nightly Expiry Job. */
    List<PointCohort> findByProgramIdAndExpiresAtLessThanOrderByEarnedAtAsc(Long programId, Instant cutoff);
}
