package io.github.batnam.loyalty.core.domain.member;

import io.github.batnam.loyalty.core.domain.event.PointsAdjusted;
import io.github.batnam.loyalty.core.domain.event.PointsExpired;
import io.github.batnam.loyalty.core.domain.ledger.EntryType;
import io.github.batnam.loyalty.core.domain.ledger.NewLedgerEntry;
import io.github.batnam.loyalty.core.domain.tier.TierLadder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the {@code Expired} / {@code Adjusted} posting paths on the Member aggregate. */
class MemberExpireAdjustTest {

    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");

    private static TierLadder ladder() {
        return TierLadder.of(List.of(
                new TierLadder.TierRung("BRONZE", 0, 0),
                new TierLadder.TierRung("SILVER", 1, 1_000),
                new TierLadder.TierRung("GOLD", 2, 5_000)));
    }

    @Test
    void appendExpiredDropsBothBalancesAndRecomputesTierDown() {
        Member m = Member.rehydrate(1L, 10L, 99L, MemberStatus.ACTIVE, 1_200, 1_200, "SILVER", 1);

        m.appendExpired(300, "expiry:1", T0, ladder());

        assertThat(m.redeemableBalance()).isEqualTo(900);
        assertThat(m.qualifyingBalance()).isEqualTo(900);
        assertThat(m.currentTierCode()).isEqualTo("BRONZE");   // dropped from SILVER to the 0-threshold BRONZE rung
        NewLedgerEntry e = m.pendingEntries().get(0);
        assertThat(e.type()).isEqualTo(EntryType.Expired);
        assertThat(e.qualifyingDelta()).isEqualTo(-300);
        assertThat(e.redeemableDelta()).isEqualTo(-300);
        assertThat(m.recordedEvents().get(0)).isInstanceOf(PointsExpired.class);
        assertThat(m.recordedEvents().get(0).eventName()).isEqualTo("PointsExpired");
    }

    @Test
    void appendAdjustedAppliesSignedDeltasAndRecomputesTierWhenQualifyingMoves() {
        Member m = Member.rehydrate(1L, 10L, 99L, MemberStatus.ACTIVE, 0, 0, null, 1);

        m.appendAdjusted(1_500, 1_500, "goodwill", 77L, "approval:77", T0, ladder());

        assertThat(m.redeemableBalance()).isEqualTo(1_500);
        assertThat(m.qualifyingBalance()).isEqualTo(1_500);
        assertThat(m.currentTierCode()).isEqualTo("SILVER");
        NewLedgerEntry e = m.pendingEntries().get(0);
        assertThat(e.type()).isEqualTo(EntryType.Adjusted);
        assertThat(e.reason()).isEqualTo("goodwill");
        assertThat(e.approvalRequestId()).isEqualTo(77L);
        assertThat(m.recordedEvents().get(0)).isInstanceOf(PointsAdjusted.class);
    }

    @Test
    void redeemableOnlyAdjustmentDoesNotMoveTier() {
        Member m = Member.rehydrate(1L, 10L, 99L, MemberStatus.ACTIVE, 5_000, 6_000, "GOLD", 1);

        m.appendAdjusted(0, -100, "clear small negative", 78L, "approval:78", T0, ladder());

        assertThat(m.redeemableBalance()).isEqualTo(4_900);
        assertThat(m.qualifyingBalance()).isEqualTo(6_000);
        assertThat(m.currentTierCode()).isEqualTo("GOLD");   // unchanged: qualifyingDelta == 0
    }
}
