package io.github.batnam.loyalty.redemption.saga;

/**
 * The next action the Redemption Orchestrator must take after a fulfilment step, decided by the pure
 * {@link SagaDecider}. Extracting the two-phase commit/release/park branches into a sealed
 * action — rather than inline {@code switch} + {@code try/catch} in the orchestrator — turns the
 * compensation matrix into an exhaustive, table-testable decision with no HTTP mocks.
 *
 * <p>The orchestrator is the only thing that <i>executes</i> these (Ledger commit/release, inventory
 * restore, saga transition, outbox emit); the decider only <i>chooses</i> them.
 */
public sealed interface SagaAction {

    /** Fulfilment succeeded: commit the reservation in core with {@code outcomeRef}, then emit Completed. */
    record Commit(String outcomeRef) implements SagaAction {}

    /** Fulfilment handed off to a partner: park the saga at {@code FULFILLING}, keyed by {@code externalRef}. */
    record Park(String externalRef) implements SagaAction {}

    /**
     * Fulfilment failed: release the reservation (passing {@code ledgerDetail} to core), restore
     * inventory, fail the saga with {@code failureReason}, then emit Failed.
     *
     * @param ledgerDetail  the partner-supplied detail forwarded to {@code ledger.release}.
     * @param failureReason the canonical reason stamped on the saga + the RedemptionFailed event.
     */
    record Release(String ledgerDetail, String failureReason) implements SagaAction {}
}
