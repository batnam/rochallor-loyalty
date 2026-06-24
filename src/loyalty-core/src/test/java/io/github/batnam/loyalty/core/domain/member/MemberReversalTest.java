package io.github.batnam.loyalty.core.domain.member;

import io.github.batnam.loyalty.core.domain.cohort.OpenCohort;
import io.github.batnam.loyalty.core.domain.event.PointsReversed;
import io.github.batnam.loyalty.core.domain.ledger.EntryType;
import io.github.batnam.loyalty.core.domain.ledger.NewLedgerEntry;
import io.github.batnam.loyalty.core.domain.tier.TierLadder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the {@code Reversed} (payment-reversal clawback) path on the Member aggregate. */
class MemberReversalTest {

    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");

    private static TierLadder ladder() {
        return TierLadder.of(List.of(
                new TierLadder.TierRung("BRONZE", 0, 0),
                new TierLadder.TierRung("SILVER", 1, 1_000),
                new TierLadder.TierRung("GOLD", 2, 5_000)));
    }

    @Test
    void appendReversedSubtractsBothBalancesRecomputesTierAndEmitsEntryAndEvent() {
        Member m = Member.rehydrate(1L, 10L, 99L, MemberStatus.ACTIVE, 1_200, 1_200, "SILVER", 1);

        m.appendReversed(300, 300, "evt-7:rule-A", T0, ladder());

        assertThat(m.redeemableBalance()).isEqualTo(900);
        assertThat(m.qualifyingBalance()).isEqualTo(900);
        assertThat(m.currentTierCode()).isEqualTo("BRONZE");   // dropped from SILVER

        NewLedgerEntry e = m.pendingEntries().get(0);
        assertThat(e.type()).isEqualTo(EntryType.Reversed);
        assertThat(e.qualifyingDelta()).isEqualTo(-300);
        assertThat(e.redeemableDelta()).isEqualTo(-300);
        assertThat(e.sourceRef()).isEqualTo("evt-7:rule-A");

        assertThat(m.recordedEvents().get(0)).isInstanceOf(PointsReversed.class);
        assertThat(m.recordedEvents().get(0).eventName()).isEqualTo("PointsReversed");
        assertThat(m.recordedEvents().get(0).qualifyingDelta()).isEqualTo(-300);
        assertThat(m.recordedEvents().get(0).redeemableDelta()).isEqualTo(-300);
    }

    @Test
    void appendReversedConsumesOpenCohortsFifo() {
        OpenCohort older = new OpenCohort(100L, 200, 0, 0, T0.minusSeconds(7200), T0.plusSeconds(86400));
        OpenCohort newer = new OpenCohort(200L, 200, 0, 0, T0.minusSeconds(3600), T0.plusSeconds(86400));
        Member m = Member.rehydrate(1L, 10L, 99L, MemberStatus.ACTIVE, 400, 400, "BRONZE", 1,
                List.of(older, newer));

        m.appendReversed(0, 300, "evt-9:rule-A", T0, ladder());

        // FIFO: drains the oldest cohort fully (200) then 100 from the newer.
        assertThat(older.consumedThisTx()).isEqualTo(200);
        assertThat(newer.consumedThisTx()).isEqualTo(100);
        assertThat(m.consumedCohorts()).containsExactly(older, newer);
        assertThat(m.redeemableBalance()).isEqualTo(100);
    }

    @Test
    void appendReversedAllowsNegativeBalanceAndToleratesCohortExhaustion() {
        // Only 100 redeemable left but reversing 300 — balance goes negative, cohort exhaustion tolerated.
        OpenCohort only = new OpenCohort(100L, 100, 0, 0, T0.minusSeconds(3600), T0.plusSeconds(86400));
        Member m = Member.rehydrate(1L, 10L, 99L, MemberStatus.ACTIVE, 100, 0, "BRONZE", 1, List.of(only));

        m.appendReversed(0, 300, "evt-3:rule-A", T0, ladder());

        assertThat(m.redeemableBalance()).isEqualTo(-200);
        assertThat(only.consumedThisTx()).isEqualTo(100);   // exhausted, remainder untracked
        assertThat(m.pendingEntries().get(0).redeemableDelta()).isEqualTo(-300);
    }

    @Test
    void appendReversedDoesNotMoveTierWhenQualifyingUnchanged() {
        Member m = Member.rehydrate(1L, 10L, 99L, MemberStatus.ACTIVE, 5_000, 6_000, "GOLD", 1);

        m.appendReversed(0, 100, "evt-1:rule-A", T0, ladder());

        assertThat(m.qualifyingBalance()).isEqualTo(6_000);
        assertThat(m.currentTierCode()).isEqualTo("GOLD");   // unchanged: qualifyingDelta == 0
    }
}
