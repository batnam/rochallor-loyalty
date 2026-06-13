package io.github.batnam.loyalty.redemption;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * loyalty-redemption — the two-phase redemption Saga (C4 L3 {@code loyalty-redemption}).
 *
 * <p>Co-locates the Reward and Fulfillment bounded contexts. On {@code submitRedemption} the Saga
 * gates Eligibility, reserves points in loyalty-core, dispatches a {@code FulfillmentAdapter} by Reward
 * Type, then commits (or releases on failure). The three sub-second adapters (Cashback, Bill-Payment
 * Voucher, Sweepstakes) run in-process; the partner-dependent 3rd-Party Voucher path returns
 * {@code FULFILLING} and is resumed asynchronously by the Resume Consumer when the partner webhook
 * lands (via {@code loyalty.fulfillment.resume.v1}). Outcomes are published through the transactional
 * outbox. Scheduled jobs (Outbox Relay) run in-pod under ShedLock.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class RedemptionApplication {
    public static void main(String[] args) {
        SpringApplication.run(RedemptionApplication.class, args);
    }
}
