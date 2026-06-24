package io.github.batnam.loyalty.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised config under {@code core.*} (topic names, reservation TTL, expiry cron, outbox batch),
 * so a deployment can override without a rebuild. Mirrors the bridge's {@code BridgeTopics} approach.
 */
@ConfigurationProperties(prefix = "core")
public record CoreProperties(
        Topics topics,
        Reservation reservation,
        Expiry expiry,
        TierReeval tierReeval,
        Tcs tcs,
        Outbox outbox
) {
    public record Topics(
            String memberLifecycle,   // consumed (from loyalty-integration-bridge)
            String paymentReversed,   // consumed (from loyalty-integration-bridge)
            String ledgerEvents,      // produced
            String memberEvents       // produced
    ) {}

    public record Reservation(int defaultTtlSeconds) {}

    public record Expiry(String cron) {}

    /** Nightly windowed-Tier re-evaluation sweep (CONTEXT.md "Qualifying Metric"). */
    public record TierReeval(String cron) {}

    /** T&Cs re-acceptance grace window (CONTEXT.md "T&Cs Version") + nightly suspend-sweep cron. */
    public record Tcs(int graceDays, String cron) {}

    public record Outbox(int relayBatchSize) {}
}
