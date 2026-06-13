package io.github.batnam.loyalty.core.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuditRepository extends JpaRepository<CoreAuditLog, Long> {

    /** Most recent row, to chain the next {@code prevHash} onto. */
    Optional<CoreAuditLog> findTopByOrderByAuditIdDesc();
}
