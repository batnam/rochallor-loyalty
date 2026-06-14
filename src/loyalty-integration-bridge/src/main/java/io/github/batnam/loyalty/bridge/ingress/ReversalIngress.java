package io.github.batnam.loyalty.bridge.ingress;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Layer-1 ingress event: {@code loyalty.ingress.reversal.v1}.
 * The bank-side adapter reports a payment/earn reversal conforming to the Loyalty contract.
 * {@code originalEventId} is the eventId of the earn event being reversed.
 */
public record ReversalIngress(
        String eventId,
        Long customerId,
        Instant occurredAt,
        String originalEventId,
        String reversalEventId,
        BigDecimal amount,
        String currency,
        Integer schemaVersion
) {
}
