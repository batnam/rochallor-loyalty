package io.github.batnam.loyalty.earning.consume;

import java.time.Instant;
import java.util.Map;

/**
 * The canonical {@code loyalty.earn.translated.v1} event consumed from the bridge (ADR-0025) — mirrors
 * {@code io.github.batnam.loyalty.bridge.canonical.EarnEvent}. Customer-scoped (no memberId/programId — earning
 * resolves those); {@code source} is a canonical {@code earn_source_code}; {@code payload} carries the
 * translated, PAN-stripped fields the DSL rows reference (amount, currency, paymentType, ...).
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
