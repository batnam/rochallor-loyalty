package io.github.batnam.loyalty.redemption.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuditRepository extends JpaRepository<RedemptionAuditLog, Long> {

    /** Most recent row, to chain the next {@code prevHash} onto. */
    Optional<RedemptionAuditLog> findTopByOrderByAuditIdDesc();
}
