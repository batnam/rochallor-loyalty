package io.github.batnam.loyalty.bridge.translate;

import io.github.batnam.loyalty.bridge.canonical.MemberLifecycle;
import io.github.batnam.loyalty.bridge.ingress.CustomerLifecycleIngress;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleTranslatorTest {

    @Test
    void mapsCustomerClosedToCanonicalLifecycle() {
        var in = new CustomerLifecycleIngress(
                "hbp:lifecycle:CL3", 100990001L,
                Instant.parse("2026-05-30T10:00:00Z"),
                "CUSTOMER_CLOSED", "CUSTOMER_REQUEST", 1);

        MemberLifecycle out = LifecycleTranslator.translate(in);

        assertThat(out.eventType()).isEqualTo("loyalty.member.lifecycle.v1");
        assertThat(out.customerId()).isEqualTo(100990001L);             // customer-scoped, no memberId
        assertThat(out.lifecycleType()).isEqualTo("CUSTOMER_CLOSED");
    }
}
