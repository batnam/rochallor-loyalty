package io.github.batnam.loyalty.core.job;

import io.github.batnam.loyalty.core.member.Member;
import io.github.batnam.loyalty.core.member.MemberRepository;
import io.github.batnam.loyalty.core.program.TierAuthority;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Per-Program tier re-evaluation, isolated in its own transaction so the Postgres advisory lock keyed
 * by {@code programId} is held for the duration (mirrors {@link ProgramExpiryProcessor}: ShedLock gives
 * one pod per tick, the advisory lock serialises against the write path on the same Program). For each
 * ACTIVE Member it recomputes {@code current_tier_code} from the <i>windowed</i> Qualifying Balance via
 * the shared {@link TierAuthority} — the same authority the post-write path uses — picking up points
 * that have aged out of the window (CONTEXT.md "Qualifying Metric").
 */
@Component
public class TierReevaluationProcessor {

    private static final Logger log = LoggerFactory.getLogger(TierReevaluationProcessor.class);

    private final MemberRepository members;
    private final TierAuthority tierAuthority;
    private final EntityManager em;

    public TierReevaluationProcessor(MemberRepository members, TierAuthority tierAuthority, EntityManager em) {
        this.members = members;
        this.tierAuthority = tierAuthority;
        this.em = em;
    }

    @Transactional
    public int reevaluateProgram(long programId, Instant now) {
        Boolean acquired = (Boolean) em
                .createNativeQuery("select pg_try_advisory_xact_lock(:pid)")
                .setParameter("pid", programId)
                .getSingleResult();
        if (Boolean.FALSE.equals(acquired)) {
            log.debug("programId={} tier reeval skipped — advisory lock held elsewhere", programId);
            return 0;
        }

        int changed = 0;
        for (Member m : members.findActiveByProgramId(programId)) {
            if (tierAuthority.recompute(m, now)) {
                changed++;   // managed entity; setCurrentTierCode dirties it for flush
            }
        }
        if (changed > 0) log.info("re-tiered {} member(s) for programId={}", changed, programId);
        return changed;
    }
}
