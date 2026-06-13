package io.github.batnam.loyalty.adminbff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised config under {@code admin-bff.*} — the outbound base-URLs for the five services the BFF
 * aggregates. A deployment overrides these via env/config; in-cluster traffic is mTLS (out of scope here,
 * so plain HTTP for local/test). Mirrors the backend services' {@code *Properties} approach.
 */
@ConfigurationProperties(prefix = "admin-bff")
public record AdminBffProperties(
        Upstream core,
        Upstream earning,
        Upstream redemption,
        Upstream campaign,
        Upstream bridge
) {
    public record Upstream(String baseUrl) {}
}
