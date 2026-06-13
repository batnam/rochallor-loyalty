package io.github.batnam.loyalty.redemption.reward;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.redemption.elig.EligibilityRules;
import io.github.batnam.loyalty.redemption.error.RedemptionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reward Catalogue (L3 §4 component 3) — Reward CRUD + the approval gate. Authoring creates a DRAFT;
 * the {@code ACTIVE} transition and any {@code pointCost} change are approval-gated (require a
 * {@code bepApprovalRef}, mirroring core/earning's confirm seam) while {@code ARCHIVE} and inventory
 * bumps apply directly. Every admin write is hash-chain audited. Also owns the atomic inventory
 * reserve/restore the Saga calls on the hot path.
 */
@Service
public class RewardCatalogue {

    private final RewardRepository rewards;
    private final RewardEligibilityRepository eligibility;
    private final RewardInventoryRepository inventory;
    private final RewardTypeRepository types;
    private final io.github.batnam.loyalty.redemption.audit.AuditLogWriter audit;
    private final ObjectMapper mapper;

    public RewardCatalogue(RewardRepository rewards, RewardEligibilityRepository eligibility,
                           RewardInventoryRepository inventory, RewardTypeRepository types,
                           io.github.batnam.loyalty.redemption.audit.AuditLogWriter audit, ObjectMapper mapper) {
        this.rewards = rewards;
        this.eligibility = eligibility;
        this.inventory = inventory;
        this.types = types;
        this.audit = audit;
        this.mapper = mapper;
    }

    // --- reads ---------------------------------------------------------------

    public Reward get(long rewardId) {
        return rewards.findById(rewardId)
                .orElseThrow(() -> RedemptionException.notFound("REWARD_NOT_FOUND", "reward " + rewardId));
    }

    public List<Reward> listByProgram(long programId, RewardStatus statusOrNull) {
        return statusOrNull == null
                ? rewards.findByProgramIdOrderByRewardIdAsc(programId)
                : rewards.findByProgramIdAndStatusOrderByRewardIdAsc(programId, statusOrNull);
    }

    public List<Reward> listActive(long programId) {
        return rewards.findByProgramIdAndStatusOrderByRewardIdAsc(programId, RewardStatus.ACTIVE);
    }

    public List<RewardTypeEntity> listRewardTypes() {
        return types.findAll();
    }

    public java.util.Optional<RewardInventory> inventoryFor(long rewardId) {
        return inventory.findById(rewardId);
    }

    /** The eligibility gates for a Reward, or {@link EligibilityRules#NONE} when none are configured. */
    public EligibilityRules eligibilityRulesFor(long rewardId) {
        return eligibility.findById(rewardId).map(RewardEligibility::toRules).orElse(EligibilityRules.NONE);
    }

    // --- inventory (hot path) ------------------------------------------------

    /** @return true if the Reward is unlimited (no inventory row) or stock was atomically decremented. */
    @Transactional
    public boolean tryReserveInventory(long rewardId) {
        if (!inventory.existsById(rewardId)) {
            return true;   // no inventory row => unlimited
        }
        return inventory.tryReserve(rewardId) == 1;
    }

    @Transactional
    public void restoreInventory(long rewardId) {
        if (inventory.existsById(rewardId)) {
            inventory.restore(rewardId);
        }
    }

    // --- admin writes --------------------------------------------------------

    @Transactional
    public Reward createReward(String actor, long programId, String rewardTypeCode, String name,
                               long pointCost, Object fulfillmentParams, EligibilityRules rules,
                               Long inventoryTotal) {
        if (!types.existsById(rewardTypeCode)) {
            throw RedemptionException.badRequest("UNKNOWN_REWARD_TYPE", "no such reward type " + rewardTypeCode);
        }
        Reward saved = rewards.save(
                Reward.draft(programId, rewardTypeCode, name, pointCost, toJson(fulfillmentParams)));
        if (rules != null && !EligibilityRules.NONE.equals(rules)) {
            eligibility.save(RewardEligibility.of(saved.getRewardId(), rules.minTierOrdinal(),
                    rules.segment(), rules.currency(), rules.perMemberCap(), rules.minTenureDays()));
        }
        if (inventoryTotal != null) {
            inventory.save(RewardInventory.of(saved.getRewardId(), inventoryTotal));
        }
        audit.record(actor, "CREATE", "Reward", String.valueOf(saved.getRewardId()), null,
                "{\"name\":\"" + name + "\",\"pointCost\":" + pointCost + "}");
        return saved;
    }

    /** Full PATCH (loyalty-redemption.yaml). Inventory bump applies directly; ACTIVE/pointCost gated. */
    @Transactional
    public Reward updateReward(String actor, long rewardId, RewardStatus status, Long pointCost,
                               Long inventoryTotal, String bepApprovalRef) {
        Reward r = get(rewardId);
        String before = "{\"status\":\"" + r.getStatus() + "\",\"pointCost\":" + r.getPointCost() + "}";

        boolean needsApproval = status == RewardStatus.ACTIVE || pointCost != null;
        if (needsApproval && (bepApprovalRef == null || bepApprovalRef.isBlank())) {
            throw RedemptionException.conflict("MISSING_APPROVAL",
                    "ACTIVE transition or pointCost change requires a bepApprovalRef (approval reference)");
        }

        if (pointCost != null) {
            r.changePointCost(pointCost);
        }
        if (status == RewardStatus.ACTIVE) {
            r.activate();
        } else if (status == RewardStatus.ARCHIVED) {
            r.archive();
        }
        if (inventoryTotal != null) {
            inventory.save(RewardInventory.of(rewardId, inventoryTotal));
        }
        Reward saved = rewards.save(r);
        String after = "{\"status\":\"" + saved.getStatus() + "\",\"pointCost\":" + saved.getPointCost() + "}";
        audit.record(actor, "UPDATE", "Reward", String.valueOf(rewardId), before, after);
        return saved;
    }

    /** Convenience overload (no inventory change) used by the status-only PATCH path + tests. */
    @Transactional
    public Reward updateReward(String actor, long rewardId, RewardStatus status, Long pointCost,
                               String bepApprovalRef) {
        return updateReward(actor, rewardId, status, pointCost, null, bepApprovalRef);
    }

    private String toJson(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw RedemptionException.badRequest("BAD_FULFILLMENT_PARAMS", "fulfillmentParams not serializable");
        }
    }
}
