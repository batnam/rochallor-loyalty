package io.github.batnam.loyalty.earning;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * loyalty-earning — the event-driven Rule Engine (C4 L3 {@code loyalty-earning}).
 *
 * <p>Consumes translated {@code EarnEvent}s from the bridge, evaluates per-Program Earning Rules
 * (constrained JSON DSL), enforces caps, and calls loyalty-core's Ledger API to award points, then
 * publishes {@code loyalty.earning.points_earned.v1} via the transactional outbox. Stateless on the
 * hot path; the only stateful tier is its own RDS. Scheduled jobs (Cap Purge, Outbox Relay) run
 * in-pod under ShedLock.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class EarningApplication {
    public static void main(String[] args) {
        SpringApplication.run(EarningApplication.class, args);
    }
}
