package io.github.batnam.loyalty.redemption.consume;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Canonical {@code loyalty.fulfillment.resume.v1} event (consumed; produced by the bridge from a partner
 * voucher webhook — L3 §3.3). Carries the {@code externalRef} that correlates back to a parked saga, the
 * partner {@code outcome}, and any partner {@code payload} (e.g. the issued {@code voucherCode}). Unknown
 * fields are ignored so the bridge can enrich the envelope without breaking this consumer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResumeEvent(
        String eventId,
        String eventType,
        String externalRef,
        String outcome,            // SUCCESS | FAILURE
        Map<String, Object> payload
) {
    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(outcome);
    }
}
