package io.github.batnam.loyalty.earning.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuditRepository extends JpaRepository<EarningAuditLog, Long> {

    /** Most recent row, to chain the next {@code prevHash} onto. */
    Optional<EarningAuditLog> findTopByOrderByAuditIdDesc();
}
