package io.github.batnam.loyalty.campaign;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * loyalty-campaign — Campaigns + Drawings (sweepstakes) service (C4 L3 {@code loyalty-campaign}).
 *
 * <p>Co-locates the Campaign and Drawing bounded contexts. BEP authors Campaigns (time-bounded earning
 * multipliers, exposed to {@code loyalty-earning} but evaluated there) and Drawings; Members enter a
 * Drawing via {@code loyalty-redemption}'s SweepstakesAdapter (the T-13 surface). When a Drawing reaches
 * its {@code draw_at}, the in-pod Drawing Scheduler (ShedLock-guarded) fires Winner Selection, which picks
 * K winners without replacement via the Drawing's selection strategy, records immutable winner rows, and
 * publishes {@code loyalty.campaign.*} through the transactional outbox. Prize fulfilment is delegated to
 * {@code loyalty-redemption} (the only service that knows how to fulfil a Reward).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class CampaignApplication {
    public static void main(String[] args) {
        SpringApplication.run(CampaignApplication.class, args);
    }
}
