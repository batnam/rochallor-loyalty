package io.github.batnam.loyalty.bridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Velocity Anomaly detector tuning (ADR-0021 degraded-availability fraud feature). Per deployment.
 * Detection: count of earn events for one {@code customerId} within {@code windowDays} exceeding
 * {@code maxEarnsPerWindow} raises an {@code EARN_VELOCITY_SPIKE}.
 */
@ConfigurationProperties(prefix = "bridge.velocity")
public record VelocityProperties(
        boolean enabled,
        int windowDays,
        int maxEarnsPerWindow
) {
}
