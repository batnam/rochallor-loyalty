package io.github.batnam.loyalty.campaign.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuditRepository extends JpaRepository<CampaignAuditLog, Long> {

    /** Most recent row, to chain the next {@code prevHash} onto. */
    Optional<CampaignAuditLog> findTopByOrderByAuditIdDesc();
}
