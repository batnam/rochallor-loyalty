package io.github.batnam.loyalty.earning.caps;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Nightly purge of expired {@code cap_counter} windows (L3 §5: "nightly job purges expired counters").
 * DAY/MONTH counters carry an {@code expires_at}; LIFE counters have NULL and are kept forever.
 * ShedLock-guarded so exactly one pod runs each night.
 */
@Component
public class CapPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(CapPurgeJob.class);

    private final CapRepository caps;

    public CapPurgeJob(CapRepository caps) {
        this.caps = caps;
    }

    @Scheduled(cron = "${earning.cap-purge.cron}")
    @SchedulerLock(name = "capCounterPurge")
    @Transactional
    public void purge() {
        int removed = caps.purgeExpired(Instant.now());
        log.info("cap_counter purge removed {} expired window(s)", removed);
    }
}
