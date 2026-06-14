package io.github.batnam.loyalty.bridge.canonical;

import java.time.Instant;

/**
 * Canonical event: {@code loyalty.member.lifecycle.v1}. Customer-scoped — keyed on
 * customerId (a Member may not yet exist). {@code loyalty-core} consumes it.
 */
public record MemberLifecycle(
        String eventId,
        String eventType,
        Instant occurredAt,
        Integer schemaVersion,
        Long customerId,
        String lifecycleType
) {
}
