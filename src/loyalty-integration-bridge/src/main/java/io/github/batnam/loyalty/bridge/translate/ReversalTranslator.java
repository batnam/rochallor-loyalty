package io.github.batnam.loyalty.bridge.translate;

import io.github.batnam.loyalty.bridge.canonical.ReversalEvent;
import io.github.batnam.loyalty.bridge.ingress.ReversalIngress;

/**
 * Pure function: {@code loyalty.ingress.reversal.v1} → {@code loyalty.payment.reversed.v1}.
 * No routing/discriminator — a reversal is a reversal. Carries the original eventId so
 * {@code loyalty-core} can match by {@code source_ref}. No I/O, no state.
 */
public final class ReversalTranslator {

    public static final String EVENT_TYPE = "loyalty.payment.reversed.v1";

    private ReversalTranslator() {
    }

    public static ReversalEvent translate(ReversalIngress in) {
        return new ReversalEvent(
                in.eventId(),
                EVENT_TYPE,
                in.occurredAt(),
                1,
                in.customerId(),
                in.reversalEventId(),
                in.originalEventId(),
                in.amount(),
                in.currency(),
                in.occurredAt()
        );
    }
}
