package io.github.batnam.loyalty.bridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Loyalty-owned, **bank-uniform** {@code paymentType → earn_source} routing map (,
 * the two-level shape of  now owned by Loyalty). A canonical paymentType not present in
 * {@code paymentTypeToEarnSource} routes to {@code fallbackEarnSource} (EMIT_FALLBACK_AND_ALERT).
 */
@ConfigurationProperties(prefix = "bridge.payment")
public record PaymentMapping(
        Map<String, String> paymentTypeToEarnSource,
        String fallbackEarnSource
) {
    /** @return the earn_source for a canonical paymentType, or the fallback when unmapped. */
    public String earnSourceFor(String paymentType) {
        return paymentTypeToEarnSource.getOrDefault(paymentType, fallbackEarnSource);
    }

    public boolean isMapped(String paymentType) {
        return paymentTypeToEarnSource.containsKey(paymentType);
    }
}
