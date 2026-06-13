package io.github.batnam.loyalty.core.projection;

import io.github.batnam.loyalty.core.member.Member;
import io.github.batnam.loyalty.core.program.ProgramConfigService;
import io.github.batnam.loyalty.core.program.Tier;
import org.springframework.stereotype.Component;

/**
 * Single writer of {@code member.current_tier_code}. Not an aggregate — a projection recomputed any
 * time the Qualifying Balance moves (CONTEXT.md "Tier"; L3 §4). Called by the Ledger Service in the
 * same transaction whenever a posted entry has {@code qualifyingDelta != 0}.
 *
 * <p>v1 scaffold uses the denormalized {@code member.qualifyingBalance} directly as the metric.
 * Windowing for {@code ROLLING_12_MONTHS} / {@code CALENDAR_YEAR} (CONTEXT.md "Qualifying Metric")
 * is deferred — the seed Program is {@code ROLLING_12_MONTHS} but the window query is not yet wired.
 */
@Component
public class TierProjection {

    private final ProgramConfigService programConfig;

    public TierProjection(ProgramConfigService programConfig) {
        this.programConfig = programConfig;
    }

    public void recompute(Member member) {
        String newTier = programConfig.tierFor(member.getProgramId(), member.getQualifyingBalance())
                .map(Tier::getTierCode)
                .orElse(null);
        if (!java.util.Objects.equals(newTier, member.getCurrentTierCode())) {
            member.setCurrentTierCode(newTier);
        }
    }
}
