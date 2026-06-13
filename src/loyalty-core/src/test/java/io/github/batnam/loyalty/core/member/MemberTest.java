package io.github.batnam.loyalty.core.member;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Member}'s column-scoped mutators. Balance deltas are additive and may drive
 * the redeemable balance negative (CONTEXT.md "Negative Redeemable Balance"). Pure POJO; no Spring / DB.
 */
class MemberTest {

    @Test
    void enrolledMemberIsActiveWithZeroBalances() {
        Member m = Member.enroll(1L, 42L, 3);
        assertThat(m.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(m.getRedeemableBalance()).isZero();
        assertThat(m.getQualifyingBalance()).isZero();
        assertThat(m.getTcsVersionAccepted()).isEqualTo(3);
    }

    @Test
    void applyBalanceDeltaIsAdditiveOnBothBalances() {
        Member m = Member.enroll(1L, 42L, 1);
        m.applyBalanceDelta(1_000, 1_000);   // Earned(+N,+N)
        m.applyBalanceDelta(-300, 0);        // Redeemed(0,−N)
        assertThat(m.getRedeemableBalance()).isEqualTo(700);
        assertThat(m.getQualifyingBalance()).isEqualTo(1_000);
    }

    @Test
    void redeemableBalanceMayGoNegative() {
        Member m = Member.enroll(1L, 42L, 1);
        m.applyBalanceDelta(100, 100);
        m.applyBalanceDelta(-250, -250);     // clawback exceeding balance
        assertThat(m.getRedeemableBalance()).isEqualTo(-150);
    }

    @Test
    void statusAndTierMutatorsTakeEffect() {
        Member m = Member.enroll(1L, 42L, 1);
        m.setStatus(MemberStatus.CLOSED);
        m.setCurrentTierCode("GOLD");
        assertThat(m.getStatus()).isEqualTo(MemberStatus.CLOSED);
        assertThat(m.getCurrentTierCode()).isEqualTo("GOLD");
    }
}
