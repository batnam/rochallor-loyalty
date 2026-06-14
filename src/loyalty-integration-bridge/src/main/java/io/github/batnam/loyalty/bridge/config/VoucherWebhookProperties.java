package io.github.batnam.loyalty.bridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Voucher webhook auth (threat DD-2). The HMAC secret is injected from the deployment's secret
 * store (never committed). {@code timestampToleranceSeconds} bounds the replay window.
 */
@ConfigurationProperties(prefix = "bridge.voucher")
public record VoucherWebhookProperties(
        String hmacSecret,
        long timestampToleranceSeconds
) {
}
