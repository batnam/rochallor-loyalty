package io.github.batnam.loyalty.bridge.ingress;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Layer-1 ingress event: {@code loyalty.ingress.card_spend.v1} (ADR-0025).
 * A settled card purchase a Host Bank's adapter reports to Loyalty. Customer-scoped — no
 * memberId/programId (resolution happens in loyalty-earning). PII-free (no PAN/name).
 */
public record CardSpendIngress(
        String eventId,
        Long customerId,
        Instant occurredAt,
        BigDecimal amount,
        String currency,
        String mcc,
        String merchantId,
        Integer schemaVersion
) {
}
