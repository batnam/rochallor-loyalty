package io.github.batnam.loyalty.earning.source;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EarnSourceCapRepository extends JpaRepository<EarnSourceCap, EarnSourceCap.Key> {

    Optional<EarnSourceCap> findByProgramIdAndEarnSourceCode(Long programId, String earnSourceCode);
}
