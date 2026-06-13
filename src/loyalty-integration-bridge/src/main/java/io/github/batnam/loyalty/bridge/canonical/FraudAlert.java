package io.github.batnam.loyalty.bridge.canonical;

import java.time.Instant;

/**
 * Canonical event: {@code loyalty.fraud.alert.v1} (ADR-0025). Customer-scoped — the Velocity Anomaly
 * consumer detects per {@code customerId}; {@code loyalty-admin-bff} resolves the Member for display.
 * Degraded-availability feature: alerts may lag during a cold-start window rebuild.
 */
public record FraudAlert(
        String eventId,
        String eventType,
        Instant occurredAt,
        Integer schemaVersion,
        Long customerId,
        String anomalyType,
        Integer windowDays,
        Double observedRate,
        Double threshold,
        Instant detectedAt
) {
}
