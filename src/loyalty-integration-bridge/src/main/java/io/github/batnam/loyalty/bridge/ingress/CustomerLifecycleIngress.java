package io.github.batnam.loyalty.bridge.ingress;

import java.time.Instant;

/**
 * Layer-1 ingress event: {@code loyalty.ingress.customer_lifecycle.v1} (ADR-0025).
 * A customer-lifecycle change the bank reports (v1: customer closed).
 */
public record CustomerLifecycleIngress(
        String eventId,
        Long customerId,
        Instant occurredAt,
        String lifecycleType,
        String reason,
        Integer schemaVersion
) {
}
