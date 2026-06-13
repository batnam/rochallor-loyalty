package io.github.batnam.loyalty.adminbff.client;

import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.AdminReward;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.RewardCreateRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.RewardStatusRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.RewardType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Anti-Corruption boundary to loyalty-redemption: the platform Reward Type catalogue and per-Program
 * Reward authoring (all statuses). Activation (→ ACTIVE) and point-cost changes are approval-gated
 * upstream — redemption enforces the gate (returns {@code MISSING_APPROVAL} without a {@code bepApprovalRef});
 * the BFF forwards the {@code X-Actor} for the audit trail.
 */
@Component
public class RewardClient {

    private final RestClient redemption;

    public RewardClient(@Qualifier("redemptionRestClient") RestClient redemptionRestClient) {
        this.redemption = redemptionRestClient;
    }

    public List<RewardType> listRewardTypes() {
        return redemption.get()
                .uri("/reward-types")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public List<AdminReward> listRewards(long programId) {
        return redemption.get()
                .uri("/programs/{programId}/rewards", programId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public AdminReward createReward(String actor, long programId, RewardCreateRequest req) {
        return redemption.post()
                .uri("/programs/{programId}/rewards", programId)
                .header("X-Actor", actor)
                .body(req)
                .retrieve()
                .body(AdminReward.class);
    }

    public AdminReward updateRewardStatus(String actor, long rewardId, RewardStatusRequest req) {
        return redemption.patch()
                .uri("/rewards/{rewardId}", rewardId)
                .header("X-Actor", actor)
                .body(req)
                .retrieve()
                .body(AdminReward.class);
    }
}
