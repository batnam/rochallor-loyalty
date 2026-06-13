package io.github.batnam.loyalty.earning.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised config under {@code earning.*} (topic names, default Program, core base-URL, outbox
 * batch, cap-purge cron) so a deployment can override without a rebuild. Mirrors core's
 * {@code CoreProperties} / the bridge's {@code BridgeTopics} approach.
 */
@ConfigurationProperties(prefix = "earning")
public record EarningProperties(
        Topics topics,
        long defaultProgramId,
        Core core,
        Outbox outbox,
        CapPurge capPurge
) {
    public record Topics(
            String earnTranslated,   // consumed (from loyalty-integration-bridge)
            String pointsEarned      // produced
    ) {}

    public record Core(String baseUrl) {}

    public record Outbox(int relayBatchSize) {}

    public record CapPurge(String cron) {}
}
