package io.github.batnam.loyalty.core.job;

import io.github.batnam.loyalty.core.cohort.CohortRepository;
import io.github.batnam.loyalty.core.cohort.PointCohort;
import io.github.batnam.loyalty.core.ledger.LedgerService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Per-Program expiry work, isolated in its own transaction so the Postgres advisory lock keyed by
 * {@code programId} is held for the duration (L3 §3.3 — belt-and-braces with ShedLock: ShedLock gives
 * one pod per tick, the advisory lock gives no two transactions on the same Program). Writes one
 * {@code Expired} Ledger entry per unconsumed expired cohort; cohorts are idempotency-keyed by id.
 */
@Component
public class ProgramExpiryProcessor {

    private static final Logger log = LoggerFactory.getLogger(ProgramExpiryProcessor.class);

    private final CohortRepository cohorts;
    private final LedgerService ledger;
    private final EntityManager em;

    public ProgramExpiryProcessor(CohortRepository cohorts, LedgerService ledger, EntityManager em) {
        this.cohorts = cohorts;
        this.ledger = ledger;
        this.em = em;
    }

    @Transactional
    public int expireProgram(long programId, Instant now) {
        Boolean acquired = (Boolean) em
                .createNativeQuery("select pg_try_advisory_xact_lock(:pid)")
                .setParameter("pid", programId)
                .getSingleResult();
        if (Boolean.FALSE.equals(acquired)) {
            log.debug("programId={} expiry skipped — advisory lock held elsewhere", programId);
            return 0;
        }

        List<PointCohort> expiredCohorts =
                cohorts.findByProgramIdAndExpiresAtLessThanOrderByEarnedAtAsc(programId, now);
        int expiredCount = 0;
        for (PointCohort c : expiredCohorts) {
            long remaining = c.remaining();
            if (remaining <= 0) continue;
            c.expire(remaining);
            cohorts.save(c);
            ledger.appendExpired(c.getMemberId(), programId, remaining, "expiry-cohort-" + c.getCohortId());
            expiredCount++;
        }
        if (expiredCount > 0) log.info("expired {} cohort(s) for programId={}", expiredCount, programId);
        return expiredCount;
    }
}
