package io.github.batnam.loyalty.campaign.campaign;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    List<Campaign> findByProgramIdOrderByCampaignIdAsc(long programId);

    List<Campaign> findByProgramIdAndStatusOrderByCampaignIdAsc(long programId, CampaignStatus status);
}
