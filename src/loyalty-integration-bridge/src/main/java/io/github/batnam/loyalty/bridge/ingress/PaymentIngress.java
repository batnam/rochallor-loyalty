package io.github.batnam.loyalty.bridge.ingress;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Layer-1 ingress event: {@code loyalty.ingress.payment.v1} (ADR-0025).
 * A completed non-card payment, discriminated by canonical {@code paymentType}. Customer-scoped,
 * PII-free. Optional sub-fields support DSL discrimination (e.g. anti-self-transfer on FUND_TRANSFER).
 */
public record PaymentIngress(
        String eventId,
        Long customerId,
        Instant occurredAt,
        BigDecimal amount,
        String currency,
        String paymentType,
        String billerCategory,
        String recipientRelationship,
        String topupTarget,
        String merchantId,
        Integer schemaVersion
) {
}
