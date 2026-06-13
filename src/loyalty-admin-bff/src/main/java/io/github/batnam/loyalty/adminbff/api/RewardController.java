package io.github.batnam.loyalty.adminbff.api;

import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.AdminReward;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.RewardCreateRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.RewardStatusRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.RewardType;
import io.github.batnam.loyalty.adminbff.client.RewardClient;
import io.github.batnam.loyalty.adminbff.security.EmployeeIdentity;
import io.github.batnam.loyalty.adminbff.security.Roles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Rewards (loyalty-admin-bff.yaml). Reward Type catalogue + per-Program Reward authoring, aggregated from
 * loyalty-redemption. Authoring requires the campaign-manager role; activation (→ ACTIVE) and point-cost
 * changes are approval-gated upstream (redemption returns {@code MISSING_APPROVAL} without a ref).
 */
@RestController
public class RewardController {

    private final RewardClient rewards;

    public RewardController(RewardClient rewards) {
        this.rewards = rewards;
    }

    @GetMapping("/reward-types")
    public List<RewardType> listRewardTypes(EmployeeIdentity caller) {
        caller.requireAnyRole(Roles.CAMPAIGN_MANAGER, Roles.READONLY);
        return rewards.listRewardTypes();
    }

    @GetMapping("/programs/{programId}/rewards")
    public List<AdminReward> listRewards(EmployeeIdentity caller, @PathVariable long programId) {
        caller.requireAnyRole(Roles.CAMPAIGN_MANAGER, Roles.READONLY);
        return rewards.listRewards(programId);
    }

    @PostMapping("/programs/{programId}/rewards")
    public ResponseEntity<AdminReward> createReward(EmployeeIdentity caller, @PathVariable long programId,
                                                    @RequestBody RewardCreateRequest req) {
        caller.requireAnyRole(Roles.CAMPAIGN_MANAGER);
        return ResponseEntity.status(HttpStatus.CREATED).body(rewards.createReward(caller.userId(), programId, req));
    }

    @PatchMapping("/rewards/{rewardId}")
    public AdminReward updateRewardStatus(EmployeeIdentity caller, @PathVariable long rewardId,
                                          @RequestBody RewardStatusRequest req) {
        caller.requireAnyRole(Roles.CAMPAIGN_MANAGER);
        return rewards.updateRewardStatus(caller.userId(), rewardId, req);
    }
}
