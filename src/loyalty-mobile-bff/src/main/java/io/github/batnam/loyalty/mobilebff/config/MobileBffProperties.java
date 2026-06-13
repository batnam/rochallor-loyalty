package io.github.batnam.loyalty.mobilebff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised config under {@code mobile-bff.*} — the outbound base-URLs for the three services the
 * BFF aggregates. A deployment overrides these via env/config; in-cluster traffic is mTLS (out of scope
 * here, so plain HTTP for local/test). Mirrors the backend services' {@code *Properties} approach.
 */
@ConfigurationProperties(prefix = "mobile-bff")
public record MobileBffProperties(
        Upstream core,
        Upstream redemption,
        Upstream campaign
) {
    public record Upstream(String baseUrl) {}
}
