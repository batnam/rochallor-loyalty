package io.github.batnam.loyalty.redemption.fulfil.adapter;

import io.github.batnam.loyalty.redemption.fulfil.FulfilmentResult;
import io.github.batnam.loyalty.redemption.fulfil.RewardType;
import io.github.batnam.loyalty.redemption.fulfil.SagaContext;
import io.github.batnam.loyalty.redemption.ledger.LedgerClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the three remaining v1 Reward Type adapters (L3 §3.2): MATERIAL_GOODS / CHARITY_DONATION are
 * synchronous stub fulfilments returning SUCCESS with a deterministic synthetic external ref;
 * TIER_BOOST is a cross-service-shaped adapter that (until the core qualifying-grant is wired) commits
 * the redeemable spend and records the intended qualifyingDelta on the external ref.
 */
class StubAndCrossServiceAdapterTest {

    private static SagaContext ctx(long sagaId, long rewardId, Map<String, Object> params) {
        return new SagaContext(sagaId, 1L, 42L, 99L, "0011223344", rewardId, 5000, params);
    }

    @Test
    void materialGoodsReturnsSuccessWithSyntheticShipmentId() {
        FulfilmentResult result = new MaterialGoodsAdapter().fulfil(ctx(555, 7, Map.of()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.externalRef()).isEqualTo("SHP-7-555");
    }

    @Test
    void charityDonationReturnsSuccessWithSyntheticReceiptId() {
        FulfilmentResult result = new CharityDonationAdapter().fulfil(ctx(556, 8, Map.of()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.externalRef()).isEqualTo("DON-8-556");
    }

    @Test
    void tierBoostGrantsQualifyingPointsInCoreKeyedOnSaga() {
        LedgerClient ledger = mock(LedgerClient.class);
        // member 42, program 1, delta 2000, sourceRef tierboost-557 (see ctx())
        when(ledger.grantQualifying(42L, 1L, 2000L, "tierboost-557")).thenReturn(8800L);

        FulfilmentResult result = new TierBoostAdapter(ledger).fulfil(ctx(557, 9, Map.of("qualifyingDelta", 2000)));

        assertThat(result.isSuccess()).isTrue();
        // external ref is the core ledger entry id returned by the grant
        assertThat(result.externalRef()).isEqualTo("8800");
        // idempotency key == sourceRef == "tierboost-{sagaId}", qualifying-only grant
        verify(ledger).grantQualifying(42L, 1L, 2000L, "tierboost-557");
    }

    @Test
    void tierBoostReturnsFailureWhenCoreGrantFails() {
        LedgerClient ledger = mock(LedgerClient.class);
        when(ledger.grantQualifying(anyLong(), anyLong(), anyLong(), eq("tierboost-559")))
                .thenThrow(new IllegalStateException("core down"));

        FulfilmentResult result = new TierBoostAdapter(ledger).fulfil(ctx(559, 11, Map.of("qualifyingDelta", 1000)));

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void tierBoostRequiresNumericQualifyingDelta() {
        LedgerClient ledger = mock(LedgerClient.class);

        assertThatThrownBy(() -> new TierBoostAdapter(ledger).fulfil(ctx(558, 10, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qualifyingDelta");
        verifyNoInteractions(ledger);
    }

    @Test
    void supportedTypesMatch() {
        assertThat(new MaterialGoodsAdapter().supportedType()).isEqualTo(RewardType.MATERIAL_GOODS);
        assertThat(new CharityDonationAdapter().supportedType()).isEqualTo(RewardType.CHARITY_DONATION);
        assertThat(new TierBoostAdapter(mock(LedgerClient.class)).supportedType()).isEqualTo(RewardType.TIER_BOOST);
    }
}
