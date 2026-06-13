package io.github.batnam.loyalty.bridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Topic names, externalised so a deployment can override them (ADR-0024). */
@ConfigurationProperties(prefix = "bridge.topics")
public record BridgeTopics(
        String ingressCardSpend,
        String ingressPayment,
        String ingressReversal,
        String ingressCustomerLifecycle,
        String earnTranslated,
        String paymentReversed,
        String memberLifecycle,
        String fraudAlert,
        String fulfillmentResume,
        String cardSpendDlq,
        String paymentDlq,
        String reversalDlq,
        String lifecycleDlq
) {
}
