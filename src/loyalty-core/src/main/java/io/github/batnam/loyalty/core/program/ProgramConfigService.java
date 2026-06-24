package io.github.batnam.loyalty.core.program;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Read-side helper over the seeded Program / Tier config. Used by the Tier Projection (which tier a
 * qualifying balance lands in) and the Ledger Service / Expiry Job (effective expiry months for a
 * cohort). Tier-ladder lookups are infrequent and small; no caching layer for v1.
 */
@Service
public class ProgramConfigService {

    private final ProgramRepository programs;
    private final TierRepository tiers;

    public ProgramConfigService(ProgramRepository programs, TierRepository tiers) {
        this.programs = programs;
        this.tiers = tiers;
    }

    public Program requireProgram(Long programId) {
        return programs.findById(programId)
                .orElseThrow(() -> new IllegalArgumentException("unknown programId=" + programId));
    }

    public List<Tier> tierLadder(Long programId) {
        return tiers.findByProgramIdOrderByOrdinalAsc(programId);
    }

    /** Highest tier whose threshold the qualifying balance meets, or empty if below the lowest rung. */
    public Optional<Tier> tierFor(Long programId, long qualifyingBalance) {
        Tier match = null;
        for (Tier t : tierLadder(programId)) {
            if (qualifyingBalance >= t.getQualifyingThreshold()) {
                match = t;   // ladder is ordinal-ascending, so the last match is the highest reached
            }
        }
        return Optional.ofNullable(match);
    }

    /**
     * Effective expiry months for points earned by a Member currently in {@code tierCode}: the Tier
     * Expiry Override if set, else the Program default (CONTEXT.md "Tier Expiry Override").
     */
    public int effectiveExpiryMonths(Long programId, String tierCode) {
        Program program = requireProgram(programId);
        if (tierCode != null) {
            for (Tier t : tierLadder(programId)) {
                if (t.getTierCode().equals(tierCode) && t.getExpiryMonthsOverride() != null) {
                    return t.getExpiryMonthsOverride();
                }
            }
        }
        return program.getExpiryMonths();
    }

    /**
     * Earn multiplier for points accrued by a Member currently in {@code tierCode} (CONTEXT.md
     * "Tier" earn multiplier). Defaults to 1.0 when the Member has no tier yet or the tier is not
     * found, so accrual is unscaled until a deployment configures real multipliers.
     */
    public BigDecimal earnMultiplierFor(Long programId, String tierCode) {
        if (tierCode != null) {
            for (Tier t : tierLadder(programId)) {
                if (t.getTierCode().equals(tierCode)) {
                    return t.getEarnMultiplier();
                }
            }
        }
        return BigDecimal.ONE;
    }
}
