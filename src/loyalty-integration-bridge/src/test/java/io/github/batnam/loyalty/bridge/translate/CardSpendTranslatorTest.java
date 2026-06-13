package io.github.batnam.loyalty.bridge.translate;

import io.github.batnam.loyalty.bridge.canonical.EarnEvent;
import io.github.batnam.loyalty.bridge.ingress.CardSpendIngress;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CardSpendTranslatorTest {

    @Test
    void mapsToCanonicalCardSpendEarnEvent() {
        var occurredAt = Instant.parse("2026-05-29T10:30:00Z");
        var ingress = new CardSpendIngress(
                "hbp:cardspend:TXN20260529000789",
                100990001L,
                occurredAt,
                new BigDecimal("42.50"),
                "USD",
                "5411",
                "M00012345",
                1);

        EarnEvent earn = CardSpendTranslator.translate(ingress);

        assertThat(earn.eventId()).isEqualTo(ingress.eventId());      // idempotency key carried through
        assertThat(earn.source()).isEqualTo("CARD_SPEND");            // constant — no discriminator
        assertThat(earn.eventType()).isEqualTo("loyalty.earn.translated.v1");
        assertThat(earn.customerId()).isEqualTo(100990001L);          // customer-scoped, no memberId
        assertThat(earn.occurredAt()).isEqualTo(occurredAt);
        assertThat(earn.payload())
                .containsEntry("amount", new BigDecimal("42.50"))
                .containsEntry("currency", "USD")
                .containsEntry("mcc", "5411")
                .containsEntry("merchantId", "M00012345");
    }

    @Test
    void omitsOptionalFieldsWhenAbsent() {
        var ingress = new CardSpendIngress(
                "e1", 1L, Instant.parse("2026-05-29T10:30:00Z"),
                new BigDecimal("10.00"), "VND", null, null, 1);

        EarnEvent earn = CardSpendTranslator.translate(ingress);

        assertThat(earn.payload()).containsOnlyKeys("amount", "currency");
    }
}
