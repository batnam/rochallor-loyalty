package io.github.batnam.loyalty.redemption.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised config under {@code redemption.*} (topic names, default Program, outbound base-URLs for
 * core + Payment Hub + voucher partner + campaign, reservation TTL, outbox batch) so a deployment can
 * override without a rebuild. Mirrors earning's {@code EarningProperties} approach.
 */
@ConfigurationProperties(prefix = "redemption")
public record RedemptionProperties(
        Topics topics,
        long defaultProgramId,
        Core core,
        PaymentHub paymentHub,
        VoucherPartner voucherPartner,
        Campaign campaign,
        int reservationTtlSeconds,
        Outbox outbox
) {
    public record Topics(
            String fulfillmentResume,    // consumed (3rd-party voucher webhook resume, via bridge)
            String redemptionCompleted,  // produced
            String redemptionFailed      // produced
    ) {}

    public record Core(String baseUrl) {}

    public record PaymentHub(String baseUrl) {}

    public record VoucherPartner(String baseUrl) {}

    public record Campaign(String baseUrl) {}

    public record Outbox(int relayBatchSize) {}
}
