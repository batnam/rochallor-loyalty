package io.github.batnam.loyalty.core.job;

import io.github.batnam.loyalty.core.program.Program;
import io.github.batnam.loyalty.core.program.ProgramRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Expiry Job (L3 §3.3, component 10). Nightly per-Program sweep that turns unconsumed expired Point
 * Cohorts into {@code Expired} Ledger entries (CONTEXT.md "Expiry"). ShedLock-guarded across pods;
 * each Program's actual work runs in {@link ProgramExpiryProcessor} under a per-Program advisory lock.
 */
@Component
public class ExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryJob.class);

    private final ProgramRepository programs;
    private final ProgramExpiryProcessor processor;

    public ExpiryJob(ProgramRepository programs, ProgramExpiryProcessor processor) {
        this.programs = programs;
        this.processor = processor;
    }

    @Scheduled(cron = "${core.expiry.cron}")
    @SchedulerLock(name = "pointExpiry", lockAtMostFor = "PT30M")
    public void run() {
        Instant now = Instant.now();
        int total = 0;
        for (Program p : programs.findAll()) {
            total += processor.expireProgram(p.getProgramId(), now);
        }
        log.info("expiry job finished — {} cohort(s) expired across {} program(s)", total, programs.count());
    }
}
