package io.github.batnam.loyalty.bridge.canonical;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Canonical event: {@code loyalty.payment.reversed.v1}.
 * Customer-scoped. {@code loyalty-core} reverses every ledger entry whose {@code source_ref}
 * matches the original event — the Bridge does no stream-join and writes no Ledger.
 */
public record ReversalEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        Integer schemaVersion,
        Long customerId,
        String reversalEventId,
        String originalEventId,
        BigDecimal amount,
        String currency,
        Instant reversedAt
) {
}
