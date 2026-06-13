package io.github.batnam.loyalty.core.domain.member;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the Effective Redeemable Balance calculation — no Spring, no DB. */
class RedeemableBalanceTest {

    @Test
    void effectiveIsBalanceMinusActiveHeld() {
        assertThat(RedeemableBalance.effective(1_000, 300)).isEqualTo(700);
    }

    @Test
    void canRedeemWhenEffectiveCoversPoints() {
        assertThat(RedeemableBalance.canRedeem(1_000, 300, 700)).isTrue();
        assertThat(RedeemableBalance.canRedeem(1_000, 300, 701)).isFalse();   // held leaves only 700
    }

    @Test
    void effectiveMayGoNegative() {
        assertThat(RedeemableBalance.effective(100, 250)).isEqualTo(-150);
        assertThat(RedeemableBalance.canRedeem(100, 250, 1)).isFalse();
    }
}
