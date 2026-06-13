package io.github.batnam.loyalty.redemption.saga;

import io.github.batnam.loyalty.redemption.fulfil.FulfilmentResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive table for the pure {@link SagaDecider} — the whole two-phase commit/release/park matrix
 * with zero HTTP/JPA mocks (ADR-0001). Each fulfilment {@code Kind} maps to exactly one {@link SagaAction},
 * and the SUCCESS/PENDING ref-resolution (adapter ref preferred, correlation ref as fallback) is pinned
 * for both the sync {@code submit} (no correlation ref) and async {@code resume} (correlation ref present).
 */
class SagaDeciderTest {

    @Test
    void successCommitsWithTheAdapterRefWhenPresent() {
        SagaAction action = SagaDecider.onFulfilment(FulfilmentResult.success("PH-TXN-1"), null);
        assertThat(action).isEqualTo(new SagaAction.Commit("PH-TXN-1"));
    }

    @Test
    void successFallsBackToCorrelationRefWhenAdapterRefIsNull() {
        // resume of a parked saga whose adapter outcome carries no fresh ref → use the parked externalRef.
        SagaAction action = SagaDecider.onFulfilment(new FulfilmentResult(FulfilmentResult.Kind.SUCCESS, null, null),
                "VOUCHER-REF-1");
        assertThat(action).isEqualTo(new SagaAction.Commit("VOUCHER-REF-1"));
    }

    @Test
    void successPrefersAdapterRefOverCorrelationRef() {
        SagaAction action = SagaDecider.onFulfilment(FulfilmentResult.success("FRESH-CODE"), "PARKED-REF");
        assertThat(action).isEqualTo(new SagaAction.Commit("FRESH-CODE"));
    }

    @Test
    void pendingParksAtTheAdapterRef() {
        SagaAction action = SagaDecider.onFulfilment(FulfilmentResult.pending("VOUCHER-REF-1"), null);
        assertThat(action).isEqualTo(new SagaAction.Park("VOUCHER-REF-1"));
    }

    @Test
    void pendingFallsBackToCorrelationRef() {
        SagaAction action = SagaDecider.onFulfilment(new FulfilmentResult(FulfilmentResult.Kind.PENDING, null, null),
                "CORR-9");
        assertThat(action).isEqualTo(new SagaAction.Park("CORR-9"));
    }

    @Test
    void failureReleasesWithPartnerDetailButCanonicalFailureReason() {
        SagaAction action = SagaDecider.onFulfilment(FulfilmentResult.failure("payment hub declined"), "any-ref");
        assertThat(action).isEqualTo(new SagaAction.Release("payment hub declined", SagaDecider.PARTNER_FAILURE));
    }
}
