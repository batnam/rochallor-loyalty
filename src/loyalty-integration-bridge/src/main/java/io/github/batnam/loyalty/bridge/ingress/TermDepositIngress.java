package io.github.batnam.loyalty.bridge.ingress;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Layer-1 ingress event: {@code loyalty.ingress.term_deposit.v1}.
 * A term deposit a Host Bank's adapter reports to Loyalty at opening. Customer-scoped — no
 * memberId/programId (resolution happens in loyalty-earning). PII-free.
 */
public record TermDepositIngress(
        String eventId,
        Long customerId,
        Instant occurredAt,
        BigDecimal amount,
        String currency,
        Integer termMonths,
        Integer schemaVersion
) {
}
