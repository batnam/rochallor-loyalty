package io.github.batnam.loyalty.redemption.fulfil;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the Adapter SPI dispatch contract (L3 §3.1 / §4): the Registry resolves exactly one adapter by
 * Reward Type, an unknown type is a hard error, and a duplicate registration is rejected at wiring time
 * (two beans claiming the same {@code RewardType} is a deploy bug, not a runtime guess).
 */
class AdapterRegistryTest {

    /** Minimal stub adapter for a given type that always succeeds. */
    private static FulfillmentAdapter stub(RewardType type) {
        return new FulfillmentAdapter() {
            @Override public RewardType supportedType() { return type; }
            @Override public FulfilmentResult fulfil(SagaContext ctx) {
                return FulfilmentResult.success("ext-" + type);
            }
        };
    }

    @Test
    void resolvesAdapterByRewardType() {
        AdapterRegistry registry = new AdapterRegistry(List.of(
                stub(RewardType.CASHBACK), stub(RewardType.THIRD_PARTY_VOUCHER)));

        assertThat(registry.resolve(RewardType.CASHBACK).supportedType()).isEqualTo(RewardType.CASHBACK);
        assertThat(registry.resolve(RewardType.THIRD_PARTY_VOUCHER).supportedType())
                .isEqualTo(RewardType.THIRD_PARTY_VOUCHER);
    }

    @Test
    void unknownTypeIsRejected() {
        AdapterRegistry registry = new AdapterRegistry(List.of(stub(RewardType.CASHBACK)));
        assertThatThrownBy(() -> registry.resolve(RewardType.SWEEPSTAKES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SWEEPSTAKES");
    }

    @Test
    void duplicateRegistrationIsRejectedAtWiring() {
        assertThatThrownBy(() -> new AdapterRegistry(List.of(
                stub(RewardType.CASHBACK), stub(RewardType.CASHBACK))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CASHBACK");
    }

    @Test
    void syncAdapterDoesNotImplementResumeByDefault() {
        FulfillmentAdapter sync = stub(RewardType.CASHBACK);
        assertThat(sync.resume("ext-1", new PartnerOutcome(true, "ext-1", null))).isEmpty();
    }

    @Test
    void fulfilmentResultFactoriesCarryTheRightKind() {
        assertThat(FulfilmentResult.success("e1").isSuccess()).isTrue();
        assertThat(FulfilmentResult.pending("e2").isPending()).isTrue();
        assertThat(FulfilmentResult.failure("boom").isFailure()).isTrue();
        assertThat(FulfilmentResult.failure("boom").detail()).isEqualTo("boom");
        assertThat(FulfilmentResult.pending("e2").externalRef()).isEqualTo("e2");
    }

    @Test
    void asyncAdapterCanImplementResume() {
        FulfillmentAdapter async = new FulfillmentAdapter() {
            @Override public RewardType supportedType() { return RewardType.THIRD_PARTY_VOUCHER; }
            @Override public FulfilmentResult fulfil(SagaContext ctx) { return FulfilmentResult.pending("ext"); }
            @Override public Optional<FulfilmentResult> resume(String externalRef, PartnerOutcome o) {
                return Optional.of(o.success()
                        ? FulfilmentResult.success(externalRef)
                        : FulfilmentResult.failure("partner failed"));
            }
        };
        assertThat(async.resume("ext", new PartnerOutcome(true, "ext", null)).orElseThrow().isSuccess()).isTrue();
        assertThat(async.resume("ext", new PartnerOutcome(false, "ext", null)).orElseThrow().isFailure()).isTrue();
    }
}
