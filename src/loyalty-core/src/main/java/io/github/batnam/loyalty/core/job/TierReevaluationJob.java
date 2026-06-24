package io.github.batnam.loyalty.core.job;

import io.github.batnam.loyalty.core.domain.tier.QualifyingWindows;
import io.github.batnam.loyalty.core.program.Program;
import io.github.batnam.loyalty.core.program.ProgramRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Tier Re-evaluation Job. Nightly per-Program sweep that re-tiers Members as points age out of the
 * Qualifying window (CONTEXT.md "Qualifying Metric", "Tier"). Without it a {@code ROLLING_12_MONTHS}
 * or {@code CALENDAR_YEAR} Member's Tier would only ever move on a Ledger event, never on the passage
 * of time. {@code LIFETIME} programs are skipped — their Tier only moves on events.
 *
 * <p>ShedLock-guarded across pods (one pod per tick); each Program's work runs in
 * {@link TierReevaluationProcessor} under a per-Program advisory lock — same shape as {@link ExpiryJob}.
 */
@Component
public class TierReevaluationJob {

    private static final Logger log = LoggerFactory.getLogger(TierReevaluationJob.class);

    private final ProgramRepository programs;
    private final TierReevaluationProcessor processor;

    public TierReevaluationJob(ProgramRepository programs, TierReevaluationProcessor processor) {
        this.programs = programs;
        this.processor = processor;
    }

    @Scheduled(cron = "${core.tier-reeval.cron}")
    @SchedulerLock(name = "tierReeval", lockAtMostFor = "PT30M")
    public void run() {
        Instant now = Instant.now();
        int total = 0;
        int swept = 0;
        for (Program p : programs.findAll()) {
            if (!QualifyingWindows.isWindowed(p.getQualifyingMetric())) {
                continue;   // LIFETIME — tier only moves on events
            }
            total += processor.reevaluateProgram(p.getProgramId(), now);
            swept++;
        }
        log.info("tier reeval finished — {} member(s) re-tiered across {} windowed program(s)", total, swept);
    }
}
