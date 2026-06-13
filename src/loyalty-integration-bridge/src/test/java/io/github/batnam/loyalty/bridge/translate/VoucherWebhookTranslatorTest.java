package io.github.batnam.loyalty.bridge.translate;

import io.github.batnam.loyalty.bridge.canonical.FulfillmentResume;
import io.github.batnam.loyalty.bridge.webhook.VoucherWebhookRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class VoucherWebhookTranslatorTest {

    @Test
    void mapsToFulfillmentResumeWithDeterministicEventId() {
        var req = new VoucherWebhookRequest("JOB-123", "READY", "VCH-999", "partner-ref-1");

        FulfillmentResume out = VoucherWebhookTranslator.translate(req, Instant.parse("2026-05-29T12:00:00Z"));

        assertThat(out.eventType()).isEqualTo("loyalty.fulfillment.resume.v1");
        assertThat(out.eventId()).isEqualTo("voucher-resume:JOB-123:READY"); // idempotent on partner retry
        assertThat(out.externalRef()).isEqualTo("JOB-123");                  // correlates to redemption_saga.job_handle
        assertThat(out.outcome()).isEqualTo("SUCCESS");                      // READY -> SUCCESS
        assertThat(out.payload()).containsEntry("voucherCode", "VCH-999");
        assertThat(out.payload()).containsEntry("partnerRef", "partner-ref-1");
    }
}
