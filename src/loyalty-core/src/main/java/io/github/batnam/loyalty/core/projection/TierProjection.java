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
 * <p>This LIFETIME-only recompute (denormalized {@code member.qualifyingBalance} straight into the
 * ladder) is superseded by the windowing authority: the authoritative write of
 * {@code current_tier_code} now happens via {@code infra} {@code TierAuthority}, which feeds the ladder
 * the <i>windowed</i> Qualifying Balance for the Program's metric ({@code LIFETIME} /
 * {@code ROLLING_12_MONTHS} / {@code CALENDAR_YEAR}, CONTEXT.md "Qualifying Metric") — driven both by
 * the post-write path and the nightly {@code TierReevaluationJob}.
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
