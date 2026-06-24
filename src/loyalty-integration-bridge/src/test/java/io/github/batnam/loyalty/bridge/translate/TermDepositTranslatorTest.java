package io.github.batnam.loyalty.bridge.translate;

import io.github.batnam.loyalty.bridge.canonical.EarnEvent;
import io.github.batnam.loyalty.bridge.ingress.TermDepositIngress;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TermDepositTranslatorTest {

    @Test
    void mapsToCanonicalTermDepositEarnEvent() {
        var occurredAt = Instant.parse("2026-05-29T10:30:00Z");
        var ingress = new TermDepositIngress(
                "hbp:termdeposit:TD20260529000789",
                100990001L,
                occurredAt,
                new BigDecimal("50000.00"),
                "USD",
                12,
                1);

        EarnEvent earn = TermDepositTranslator.translate(ingress);

        assertThat(earn.eventId()).isEqualTo(ingress.eventId());      // idempotency key carried through
        assertThat(earn.source()).isEqualTo("TERM_DEPOSIT_OPENED");   // constant — no discriminator
        assertThat(earn.eventType()).isEqualTo("loyalty.earn.translated.v1");
        assertThat(earn.customerId()).isEqualTo(100990001L);          // customer-scoped, no memberId
        assertThat(earn.occurredAt()).isEqualTo(occurredAt);
        assertThat(earn.payload())
                .containsEntry("amount", new BigDecimal("50000.00"))
                .containsEntry("currency", "USD")
                .containsEntry("termMonths", 12);
    }

    @Test
    void omitsOptionalFieldsWhenAbsent() {
        var ingress = new TermDepositIngress(
                "e1", 1L, Instant.parse("2026-05-29T10:30:00Z"),
                new BigDecimal("10000.00"), "VND", null, 1);

        EarnEvent earn = TermDepositTranslator.translate(ingress);

        assertThat(earn.payload()).containsOnlyKeys("amount", "currency");
    }
}
