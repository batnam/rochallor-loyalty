package io.github.batnam.loyalty.core.domain.member;

import io.github.batnam.loyalty.core.domain.cohort.OpenCohort;
import io.github.batnam.loyalty.core.domain.event.PointsRedeemed;
import io.github.batnam.loyalty.core.domain.ledger.EntryType;
import io.github.batnam.loyalty.core.domain.ledger.NewLedgerEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for FIFO cohort consumption inside the Member aggregate's {@code appendRedeemed}. */
class MemberRedeemTest {

    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-01-17T10:00:00Z");
    private static final Instant FAR = Instant.parse("2027-01-15T10:00:00Z");

    private static OpenCohort cohort(long id, long original, long consumed, Instant earnedAt, Instant expiresAt) {
        return new OpenCohort(id, original, consumed, 0, earnedAt, expiresAt);
    }

    @Test
    void consumesOldestCohortsFirst() {
        OpenCohort a = cohort(1, 100, 0, T0, FAR);
        OpenCohort b = cohort(2, 100, 0, T0.plusSeconds(86_400), FAR);
        Member m = Member.rehydrate(1L, 10L, 99L, MemberStatus.ACTIVE, 200, 200, "SILVER", 1, List.of(a, b));

        m.appendRedeemed(150, "redeem:1", NOW);

        assertThat(m.redeemableBalance()).isEqualTo(50);
        assertThat(m.qualifyingBalance()).isEqualTo(200);          // redemptions never touch qualifying
        assertThat(m.currentTierCode()).isEqualTo("SILVER");        // nor the Tier

        assertThat(m.consumedCohorts()).hasSize(2);
        assertThat(a.consumedThisTx()).isEqualTo(100);              // oldest fully drained
        assertThat(b.consumedThisTx()).isEqualTo(50);               // then the next, partially

        NewLedgerEntry e = m.pendingEntries().get(0);
        assertThat(e.type()).isEqualTo(EntryType.Redeemed);
        assertThat(e.redeemableDelta()).isEqualTo(-150);
        assertThat(e.qualifyingDelta()).isZero();
        assertThat(m.recordedEvents().get(0)).isInstanceOf(PointsRedeemed.class);
    }

    @Test
    void skipsExpiredCohorts() {
        OpenCohort expired = cohort(1, 100, 0, T0, Instant.parse("2026-01-16T10:00:00Z")); // expired at NOW
        OpenCohort live = cohort(2, 100, 0, T0.plusSeconds(86_400), FAR);
        Member m = Member.rehydrate(1L, 10L, 99L, MemberStatus.ACTIVE, 200, 200, null, 1, List.of(expired, live));

        m.appendRedeemed(50, "redeem:2", NOW);

        assertThat(expired.consumedThisTx()).isZero();              // expired cohort skipped
        assertThat(live.consumedThisTx()).isEqualTo(50);
        assertThat(m.consumedCohorts()).containsExactly(live);
    }
}
