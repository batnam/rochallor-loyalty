package io.github.batnam.loyalty.earning.source;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EarnSourceRepository extends JpaRepository<EarnSource, Long> {

    Optional<EarnSource> findByEarnSourceCode(String earnSourceCode);
}
