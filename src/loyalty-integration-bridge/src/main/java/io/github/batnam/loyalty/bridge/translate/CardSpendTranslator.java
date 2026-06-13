package io.github.batnam.loyalty.bridge.translate;

import io.github.batnam.loyalty.bridge.canonical.EarnEvent;
import io.github.batnam.loyalty.bridge.ingress.CardSpendIngress;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure function: {@code loyalty.ingress.card_spend.v1} → {@code loyalty.earn.translated.v1}.
 * No I/O, no state — unit-testable in isolation. card_spend has no discriminator, so {@code source}
 * is the constant {@code CARD_SPEND} (ADR-0025).
 */
public final class CardSpendTranslator {

    public static final String EARN_SOURCE = "CARD_SPEND";
    public static final String EVENT_TYPE = "loyalty.earn.translated.v1";

    private CardSpendTranslator() {
    }

    public static EarnEvent translate(CardSpendIngress in) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", in.amount());
        payload.put("currency", in.currency());
        if (in.mcc() != null) {
            payload.put("mcc", in.mcc());
        }
        if (in.merchantId() != null) {
            payload.put("merchantId", in.merchantId());
        }
        return new EarnEvent(
                in.eventId(),          // idempotency key carried through
                EVENT_TYPE,
                in.occurredAt(),
                1,
                in.customerId(),
                EARN_SOURCE,
                payload
        );
    }
}
