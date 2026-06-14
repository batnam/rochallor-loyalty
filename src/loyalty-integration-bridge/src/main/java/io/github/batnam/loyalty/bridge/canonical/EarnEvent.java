package io.github.batnam.loyalty.bridge.canonical;

import java.time.Instant;
import java.util.Map;

/**
 * Layer-2 canonical event: {@code loyalty.earn.translated.v1}.
 * Customer-scoped envelope (no programId — the Bridge resolves no Program); {@code source} is a
 * canonical {@code earn_source_code}. Consumed unchanged by loyalty-earning.
 */
public record EarnEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        Integer schemaVersion,
        Long customerId,
        String source,
        Map<String, Object> payload
) {
}
