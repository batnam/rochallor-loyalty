package io.github.batnam.loyalty.campaign.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised config under {@code campaign.*} (topic names, default Program, the selection HMAC secret,
 * scheduler cadence, outbox batch) so a deployment can override without a rebuild. Mirrors
 * redemption's {@code RedemptionProperties} approach.
 */
@ConfigurationProperties(prefix = "campaign")
public record CampaignProperties(
        Topics topics,
        long defaultProgramId,
        String defaultProgramCode,
        Selection selection,
        Scheduler scheduler,
        Outbox outbox
) {
    public record Topics(
            String drawingCompleted,  // produced (winners selected)
            String winnerSelected,    // produced (one per winner)
            String drawingVoid        // produced (zero-entry drawing; C4 §3.3)
    ) {}

    /** HMAC secret for SEEDED_RNG/WEIGHTED seed derivation (L3 §3.3); the only non-public selection input. */
    public record Selection(String hmacSecret) {}

    public record Scheduler(String pollCron) {}

    public record Outbox(int relayBatchSize) {}
}
