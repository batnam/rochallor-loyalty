package io.github.batnam.loyalty.core.program;

import io.github.batnam.loyalty.core.member.Member;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Authoritative recompute of {@code member.current_tier_code} from the <i>windowed</i> Qualifying
 * Balance (CONTEXT.md "Tier", "Qualifying Metric"). The single implementation shared by the
 * post-write path ({@code JpaMembers.save}) and the nightly {@code TierReevaluationJob}, so both
 * agree on the metric.
 *
 * <p>Lives in {@code :infra} so it can see {@link QualifyingWindow} (Ledger SUM) and the
 * Program/Tier entities; the {@code :app} {@code ProgramConfigService} is not on this classpath, so
 * the "highest threshold met" ladder loop is replicated here against {@link TierRepository} (mirrors
 * {@code ProgramConfigService.tierFor}).
 */
@Component
public class TierAuthority {

    private final QualifyingWindow window;
    private final ProgramRepository programs;
    private final TierRepository tiers;

    public TierAuthority(QualifyingWindow window, ProgramRepository programs, TierRepository tiers) {
        this.window = window;
        this.programs = programs;
        this.tiers = tiers;
    }

    /**
     * Recompute the Member's Tier from its windowed Qualifying Balance at {@code now} and write
     * {@code current_tier_code} if it changed (the single-writer Tier projection path, P5). Returns
     * true when the Tier moved. The cumulative {@code qualifyingBalance} on the entity is used as-is
     * for {@code LIFETIME} programs (no Ledger query).
     */
    public boolean recompute(Member member, Instant now) {
        String metric = programs.findById(member.getProgramId())
                .orElseThrow(() -> new IllegalStateException("unknown programId=" + member.getProgramId()))
                .getQualifyingMetric();

        long qualifying = window.windowedQualifying(member.getMemberId(), member.getProgramId(),
                metric, member.getQualifyingBalance(), now);

        String newTier = tierFor(member.getProgramId(), qualifying).map(Tier::getTierCode).orElse(null);
        if (!java.util.Objects.equals(newTier, member.getCurrentTierCode())) {
            member.setCurrentTierCode(newTier);
            return true;
        }
        return false;
    }

    /** Highest tier whose threshold the qualifying balance meets (ordinal-ascending ladder). */
    private Optional<Tier> tierFor(Long programId, long qualifyingBalance) {
        Tier match = null;
        for (Tier t : tiers.findByProgramIdOrderByOrdinalAsc(programId)) {
            if (qualifyingBalance >= t.getQualifyingThreshold()) {
                match = t;   // last match is the highest reached
            }
        }
        return Optional.ofNullable(match);
    }
}
