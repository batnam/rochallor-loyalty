package io.github.batnam.loyalty.bridge.translate;

import io.github.batnam.loyalty.bridge.canonical.EarnEvent;
import io.github.batnam.loyalty.bridge.ingress.TermDepositIngress;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure function: {@code loyalty.ingress.term_deposit.v1} → {@code loyalty.earn.translated.v1}.
 * No I/O, no state — unit-testable in isolation. term_deposit has no discriminator, so {@code source}
 * is the constant {@code TERM_DEPOSIT_OPENED}.
 */
public final class TermDepositTranslator {

    public static final String EARN_SOURCE = "TERM_DEPOSIT_OPENED";
    public static final String EVENT_TYPE = "loyalty.earn.translated.v1";

    private TermDepositTranslator() {
    }

    public static EarnEvent translate(TermDepositIngress in) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", in.amount());
        payload.put("currency", in.currency());
        if (in.termMonths() != null) {
            payload.put("termMonths", in.termMonths());
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
