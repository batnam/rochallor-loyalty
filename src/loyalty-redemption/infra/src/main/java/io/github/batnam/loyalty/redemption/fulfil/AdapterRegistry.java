package io.github.batnam.loyalty.redemption.fulfil;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the one {@link FulfillmentAdapter} for a {@link RewardType} (L3 §3.1 dispatch). Built from
 * every adapter bean on the classpath; a duplicate type is a wiring bug and fails fast at construction,
 * and an unresolvable type is a hard error rather than a silent skip — so the Saga can never dispatch
 * into the void.
 */
@Component
public class AdapterRegistry {

    private final Map<RewardType, FulfillmentAdapter> byType = new EnumMap<>(RewardType.class);

    public AdapterRegistry(List<FulfillmentAdapter> adapters) {
        for (FulfillmentAdapter a : adapters) {
            FulfillmentAdapter prev = byType.put(a.supportedType(), a);
            if (prev != null) {
                throw new IllegalStateException("two adapters registered for RewardType " + a.supportedType()
                        + ": " + prev.getClass().getName() + " and " + a.getClass().getName());
            }
        }
    }

    public FulfillmentAdapter resolve(RewardType type) {
        FulfillmentAdapter a = byType.get(type);
        if (a == null) {
            throw new IllegalStateException("no FulfillmentAdapter registered for RewardType " + type);
        }
        return a;
    }
}
