package io.github.batnam.loyalty.earning.source;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface RuleRepository extends JpaRepository<EarningRule, Long> {

    List<EarningRule> findByProgramId(Long programId);

    List<EarningRule> findByProgramIdAndStatus(Long programId, RuleStatus status);

    /**
     * Hot-path resolution: ACTIVE rules for a (program, source) whose validity window contains
     * {@code now}. Multiple may match — each fires and contributes its own Ledger entry (CONTEXT.md
     * "Conflict" = sum).
     */
    @Query("""
            SELECT r FROM EarningRule r
            WHERE r.programId = :programId AND r.earnSourceId = :earnSourceId AND r.status = 'ACTIVE'
              AND (r.effectiveFrom IS NULL OR r.effectiveFrom <= :now)
              AND (r.effectiveTo   IS NULL OR r.effectiveTo   >= :now)
            ORDER BY r.ruleId
            """)
    List<EarningRule> findActiveForSource(@Param("programId") Long programId,
                                          @Param("earnSourceId") Long earnSourceId,
                                          @Param("now") Instant now);
}
