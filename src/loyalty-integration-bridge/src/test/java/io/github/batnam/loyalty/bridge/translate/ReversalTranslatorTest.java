package io.github.batnam.loyalty.bridge.translate;

import io.github.batnam.loyalty.bridge.canonical.ReversalEvent;
import io.github.batnam.loyalty.bridge.ingress.ReversalIngress;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReversalTranslatorTest {

    @Test
    void carriesOriginalEventIdForSourceRefMatch() {
        var occurredAt = Instant.parse("2026-05-30T09:00:00Z");
        var in = new ReversalIngress(
                "hbp:reversal:RV45", 100990001L, occurredAt,
                "hbp:cardspend:TXN789", "RV45",
                new BigDecimal("42.50"), "USD", 1);

        ReversalEvent out = ReversalTranslator.translate(in);

        assertThat(out.eventType()).isEqualTo("loyalty.payment.reversed.v1");
        assertThat(out.customerId()).isEqualTo(100990001L);
        assertThat(out.originalEventId()).isEqualTo("hbp:cardspend:TXN789"); // loyalty-core matches source_ref
        assertThat(out.reversalEventId()).isEqualTo("RV45");
        assertThat(out.reversedAt()).isEqualTo(occurredAt);
    }
}
