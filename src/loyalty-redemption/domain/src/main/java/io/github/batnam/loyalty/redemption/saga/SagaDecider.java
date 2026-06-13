package io.github.batnam.loyalty.redemption.saga;

import io.github.batnam.loyalty.redemption.fulfil.FulfilmentResult;

/**
 * Pure two-phase Saga decision function (L3 §3.2/§3.3, ADR-0001): given a {@link FulfilmentResult}
 * (the adapter's step outcome) and the saga's correlation ref, it returns the {@link SagaAction} the
 * orchestrator must execute. No I/O, no Spring — so the whole reserve→commit/release/park matrix is a
 * unit-test table rather than branches buried in {@code try/catch} around HTTP calls.
 *
 * <p>The same decision serves both the synchronous {@code submit} phase-2 (correlation ref absent —
 * the adapter always supplies its own ref) and the asynchronous {@code resume} (correlation ref is the
 * parked {@code externalRef}, used as a fallback when the resumed outcome carries no fresh ref).
 */
public final class SagaDecider {

    /** Canonical reason stamped on a saga + the RedemptionFailed event when fulfilment fails. */
    public static final String PARTNER_FAILURE = "PARTNER_FAILURE";

    private SagaDecider() {
    }

    public static SagaAction onFulfilment(FulfilmentResult result, String correlationRef) {
        return switch (result.kind()) {
            case SUCCESS -> new SagaAction.Commit(refOr(result.externalRef(), correlationRef));
            case PENDING -> new SagaAction.Park(refOr(result.externalRef(), correlationRef));
            case FAILURE -> new SagaAction.Release(result.detail(), PARTNER_FAILURE);
        };
    }

    private static String refOr(String primary, String fallback) {
        return primary != null ? primary : fallback;
    }
}
