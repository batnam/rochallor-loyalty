package io.github.batnam.loyalty.core.program;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProgramConfigService}'s pure selection logic: which Tier a qualifying balance
 * lands in (highest threshold met, ordinal-ascending ladder) and the effective expiry months (Tier
 * Expiry Override falling back to the Program default). Repositories and config rows are mocked.
 */
class ProgramConfigServiceTest {

    private final ProgramRepository programs = mock(ProgramRepository.class);
    private final TierRepository tiers = mock(TierRepository.class);
    private final ProgramConfigService service = new ProgramConfigService(programs, tiers);

    private static Tier tier(String code, long threshold, Integer expiryOverride) {
        Tier t = mock(Tier.class);
        when(t.getTierCode()).thenReturn(code);
        when(t.getQualifyingThreshold()).thenReturn(threshold);
        when(t.getExpiryMonthsOverride()).thenReturn(expiryOverride);
        return t;
    }

    private void seedLadder() {
        // Bronze(0) → Silver(50_000) → Gold(200_000), ordinal-ascending as the repo returns it.
        // Build the tiers (each does its own stubbing) before opening the repo stub, otherwise the
        // nested when() calls land inside the unfinished findBy... stub (UnfinishedStubbingException).
        List<Tier> ladder = List.of(
                tier("BRONZE", 0, null),
                tier("SILVER", 50_000, 36),
                tier("GOLD", 200_000, 60));
        when(tiers.findByProgramIdOrderByOrdinalAsc(anyLong())).thenReturn(ladder);
    }

    @Test
    void tierForPicksHighestThresholdMet() {
        seedLadder();
        assertThat(service.tierFor(1L, 0).map(Tier::getTierCode)).contains("BRONZE");
        assertThat(service.tierFor(1L, 49_999).map(Tier::getTierCode)).contains("BRONZE");
        assertThat(service.tierFor(1L, 50_000).map(Tier::getTierCode)).contains("SILVER");   // inclusive
        assertThat(service.tierFor(1L, 199_999).map(Tier::getTierCode)).contains("SILVER");
        assertThat(service.tierFor(1L, 1_000_000).map(Tier::getTierCode)).contains("GOLD");
    }

    @Test
    void tierForIsEmptyBelowTheLowestRung() {
        seedLadder();
        // Negative qualifying balance falls below BRONZE(0) → no tier reached.
        assertThat(service.tierFor(1L, -1)).isEmpty();
    }

    @Test
    void effectiveExpiryMonthsUsesTierOverrideWhenPresent() {
        Program program = mock(Program.class);
        when(program.getExpiryMonths()).thenReturn(24);
        when(programs.findById(1L)).thenReturn(Optional.of(program));
        seedLadder();

        assertThat(service.effectiveExpiryMonths(1L, "SILVER")).isEqualTo(36);
    }

    @Test
    void effectiveExpiryMonthsFallsBackToProgramDefault() {
        Program program = mock(Program.class);
        when(program.getExpiryMonths()).thenReturn(24);
        when(programs.findById(1L)).thenReturn(Optional.of(program));
        seedLadder();

        assertThat(service.effectiveExpiryMonths(1L, "BRONZE")).isEqualTo(24);   // override null → default
        assertThat(service.effectiveExpiryMonths(1L, null)).isEqualTo(24);       // no tier → default
    }

    @Test
    void earnMultiplierForReturnsTheTiersConfiguredMultiplier() {
        Tier silver = tier("SILVER", 50_000, 36);
        when(silver.getEarnMultiplier()).thenReturn(new BigDecimal("1.500"));
        when(tiers.findByProgramIdOrderByOrdinalAsc(anyLong())).thenReturn(List.of(silver));

        assertThat(service.earnMultiplierFor(1L, "SILVER")).isEqualByComparingTo("1.500");
    }

    @Test
    void earnMultiplierForDefaultsToOneWhenNoTierOrTierNotFound() {
        seedLadder();   // ladder has no earn_multiplier stubbed; lookups below never reach a match

        assertThat(service.earnMultiplierFor(1L, null)).isEqualByComparingTo("1.0");      // no current tier
        assertThat(service.earnMultiplierFor(1L, "PLATINUM")).isEqualByComparingTo("1.0"); // unknown tier
    }
}
