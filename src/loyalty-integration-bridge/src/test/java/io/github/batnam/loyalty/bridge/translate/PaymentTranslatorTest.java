package io.github.batnam.loyalty.bridge.translate;

import io.github.batnam.loyalty.bridge.canonical.EarnEvent;
import io.github.batnam.loyalty.bridge.config.PaymentMapping;
import io.github.batnam.loyalty.bridge.ingress.PaymentIngress;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentTranslatorTest {

    private static final PaymentMapping MAPPING = new PaymentMapping(
            Map.of(
                    "BILL_PAYMENT", "BILL_PAYMENT",
                    "FUND_TRANSFER", "FUND_TRANSFER",
                    "P2P_TRANSFER", "FUND_TRANSFER",
                    "QR_PAYMENT", "FUND_TRANSFER",
                    "TOPUP", "TOPUP"),
            "PAYMENT_COMPLETED");

    private static PaymentIngress payment(String paymentType) {
        return new PaymentIngress(
                "hbp:payment:TXN1", 100990001L,
                Instant.parse("2026-05-29T11:00:00Z"),
                new BigDecimal("50.00"), "USD",
                paymentType, null, null, null, null, 1);
    }

    @Test
    void routesP2pToFundTransferAndPreservesPaymentType() {
        EarnEvent earn = PaymentTranslator.translate(payment("P2P_TRANSFER"), MAPPING);

        assertThat(earn.source()).isEqualTo("FUND_TRANSFER");               // routing
        assertThat(earn.payload()).containsEntry("paymentType", "P2P_TRANSFER"); // preserved for DSL exclusion
        assertThat(earn.customerId()).isEqualTo(100990001L);
        assertThat(earn.eventType()).isEqualTo("loyalty.earn.translated.v1");
    }

    @Test
    void routesBillPaymentAndCarriesSubFields() {
        var in = new PaymentIngress(
                "e2", 1L, Instant.parse("2026-05-29T11:00:00Z"),
                new BigDecimal("12.00"), "USD",
                "BILL_PAYMENT", "UTILITY", null, null, null, 1);

        EarnEvent earn = PaymentTranslator.translate(in, MAPPING);

        assertThat(earn.source()).isEqualTo("BILL_PAYMENT");
        assertThat(earn.payload())
                .containsEntry("paymentType", "BILL_PAYMENT")
                .containsEntry("billerCategory", "UTILITY");
    }

    @Test
    void unmappedPaymentTypeFallsBackToInactiveSource() {
        EarnEvent earn = PaymentTranslator.translate(payment("OTHER"), MAPPING);

        assertThat(earn.source()).isEqualTo("PAYMENT_COMPLETED");           // EMIT_FALLBACK_AND_ALERT
        assertThat(earn.payload()).containsEntry("paymentType", "OTHER");
    }
}
