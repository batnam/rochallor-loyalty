package io.github.batnam.loyalty.core.program;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TierRepository extends JpaRepository<Tier, Long> {

    /** Tier ladder for a Program, lowest rung first. */
    List<Tier> findByProgramIdOrderByOrdinalAsc(Long programId);
}
