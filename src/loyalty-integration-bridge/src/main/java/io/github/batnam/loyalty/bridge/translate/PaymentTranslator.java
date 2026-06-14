package io.github.batnam.loyalty.bridge.translate;

import io.github.batnam.loyalty.bridge.canonical.EarnEvent;
import io.github.batnam.loyalty.bridge.config.PaymentMapping;
import io.github.batnam.loyalty.bridge.ingress.PaymentIngress;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure function: {@code loyalty.ingress.payment.v1} → {@code loyalty.earn.translated.v1}.
 * Two-level: routes canonical {@code paymentType} to an {@code earn_source} via
 * the bank-uniform {@link PaymentMapping}, and **preserves** {@code paymentType} (+ sub-fields) on
 * the payload so DSL rules can discriminate (e.g. exclude P2P_TRANSFER under FUND_TRANSFER).
 * No I/O, no state.
 */
public final class PaymentTranslator {

    public static final String EVENT_TYPE = "loyalty.earn.translated.v1";

    private PaymentTranslator() {
    }

    public static EarnEvent translate(PaymentIngress in, PaymentMapping mapping) {
        String earnSource = mapping.earnSourceFor(in.paymentType());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", in.amount());
        payload.put("currency", in.currency());
        payload.put("paymentType", in.paymentType());          // preserved for DSL discrimination
        putIfPresent(payload, "billerCategory", in.billerCategory());
        putIfPresent(payload, "recipientRelationship", in.recipientRelationship());
        putIfPresent(payload, "topupTarget", in.topupTarget());
        putIfPresent(payload, "merchantId", in.merchantId());

        return new EarnEvent(
                in.eventId(),
                EVENT_TYPE,
                in.occurredAt(),
                1,
                in.customerId(),
                earnSource,
                payload
        );
    }

    private static void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null) {
            payload.put(key, value);
        }
    }
}
