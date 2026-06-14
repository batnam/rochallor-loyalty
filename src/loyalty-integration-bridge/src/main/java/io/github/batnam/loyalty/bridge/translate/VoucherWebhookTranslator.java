package io.github.batnam.loyalty.bridge.translate;

import io.github.batnam.loyalty.bridge.canonical.FulfillmentResume;
import io.github.batnam.loyalty.bridge.webhook.VoucherWebhookRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure function: voucher webhook body → {@code loyalty.fulfillment.resume.v1}. The {@code eventId}
 * is deterministic ({@code jobHandle:status}) so a partner retry is idempotent — downstream dedups
 * on it. The partner-facing {@code status} ({@code READY}/{@code FAILED}) is normalised to the
 * canonical {@code outcome} ({@code SUCCESS}/{@code FAILURE}) the redemption Resume Consumer reads,
 * and the issued {@code voucherCode}/{@code partnerRef} are carried in {@code payload}. No I/O, no state.
 */
public final class VoucherWebhookTranslator {

    public static final String EVENT_TYPE = "loyalty.fulfillment.resume.v1";

    private VoucherWebhookTranslator() {
    }

    public static FulfillmentResume translate(VoucherWebhookRequest in, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (in.voucherCode() != null) {
            payload.put("voucherCode", in.voucherCode());
        }
        if (in.partnerRef() != null) {
            payload.put("partnerRef", in.partnerRef());
        }
        return new FulfillmentResume(
                "voucher-resume:" + in.jobHandle() + ":" + in.status(),
                EVENT_TYPE,
                now,
                1,
                in.jobHandle(),
                "READY".equals(in.status()) ? "SUCCESS" : "FAILURE",
                payload
        );
    }
}
