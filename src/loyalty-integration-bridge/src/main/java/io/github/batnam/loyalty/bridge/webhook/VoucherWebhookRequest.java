package io.github.batnam.loyalty.bridge.webhook;

/**
 * The 3rd-party voucher partner's HTTPS POST body. The partner-facing contract (fields, auth) is a
 * cross-team item (X-1); this is the v1 shape the Bridge accepts. PII-free.
 */
public record VoucherWebhookRequest(
        String jobHandle,
        String status,        // READY | FAILED
        String voucherCode,
        String partnerRef
) {
}
