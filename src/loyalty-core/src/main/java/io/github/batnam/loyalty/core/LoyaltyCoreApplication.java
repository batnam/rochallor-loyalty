package io.github.batnam.loyalty.core;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * loyalty-core — the Membership + Ledger shared kernel (C4 L3 {@code loyalty-core}).
 *
 * <p>The hot critical path of the platform. Co-locates the Membership and Ledger bounded contexts
 * in one JVM / one Postgres transaction so Ledger inserts, balance projection updates, Tier
 * re-evaluation, Reservation transitions, and the outbox enqueue all commit-or-roll-back together
 * (single-writer invariant, transactional outbox). Scheduled jobs (Expiry, TTL Sweeper) run
 * in-pod under ShedLock.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class LoyaltyCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoyaltyCoreApplication.class, args);
    }
}
