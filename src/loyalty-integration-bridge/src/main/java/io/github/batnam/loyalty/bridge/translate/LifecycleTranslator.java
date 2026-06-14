package io.github.batnam.loyalty.bridge.translate;

import io.github.batnam.loyalty.bridge.canonical.MemberLifecycle;
import io.github.batnam.loyalty.bridge.ingress.CustomerLifecycleIngress;

/**
 * Pure function: {@code loyalty.ingress.customer_lifecycle.v1} → {@code loyalty.member.lifecycle.v1}.
 * {@code lifecycleType} passes through (the contract enum is already canonical). No I/O, no state.
 */
public final class LifecycleTranslator {

    public static final String EVENT_TYPE = "loyalty.member.lifecycle.v1";

    private LifecycleTranslator() {
    }

    public static MemberLifecycle translate(CustomerLifecycleIngress in) {
        return new MemberLifecycle(
                in.eventId(),
                EVENT_TYPE,
                in.occurredAt(),
                1,
                in.customerId(),
                in.lifecycleType()
        );
    }
}
