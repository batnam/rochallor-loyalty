package io.github.batnam.loyalty.redemption.fulfil;

import java.util.Optional;

/**
 * The Fulfillment SPI (L3 §4). Each Reward Type is satisfied by exactly one implementing bean; adding a
 * type is bounded — implement this interface, register the bean, add a {@link RewardType} value. The
 * Saga is the only caller.
 *
 * <ul>
 *   <li><b>Synchronous</b> adapters (Cashback, Bill-Payment, Sweepstakes) return {@code SUCCESS} /
 *       {@code FAILURE} from {@link #fulfil} and leave {@link #resume} at its default (no async path).</li>
 *   <li><b>Asynchronous</b> adapters (3rd-Party Voucher) return {@code PENDING(externalRef)} from
 *       {@link #fulfil} and implement {@link #resume} for the partner webhook.</li>
 * </ul>
 */
public interface FulfillmentAdapter {

    RewardType supportedType();

    /** Attempt fulfilment in-process. Returns SUCCESS / FAILURE, or PENDING for a partner hand-off. */
    FulfilmentResult fulfil(SagaContext ctx);

    /**
     * Resume a parked (PENDING) fulfilment from a partner outcome. Synchronous adapters never park, so
     * the default is empty — calling it is a no-op the Orchestrator treats as "nothing to resume".
     */
    default Optional<FulfilmentResult> resume(String externalRef, PartnerOutcome outcome) {
        return Optional.empty();
    }
}
