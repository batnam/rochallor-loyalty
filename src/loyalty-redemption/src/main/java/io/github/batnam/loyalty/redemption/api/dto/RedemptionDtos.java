package io.github.batnam.loyalty.redemption.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.redemption.reward.Reward;
import io.github.batnam.loyalty.redemption.reward.RewardInventory;
import io.github.batnam.loyalty.redemption.reward.RewardTypeEntity;
import io.github.batnam.loyalty.redemption.saga.RedemptionSaga;

import java.time.Instant;

/**
 * Request/response records for the loyalty-redemption internal API (loyalty-redemption.yaml).
 *
 * <p>{@code fulfillmentParams} / {@code eligibility} / {@code parameterSchema} are carried as plain
 * {@code Object} (Map/List trees), not Jackson tree nodes: Spring Boot 4's web layer is Jackson 3
 * ({@code tools.jackson}) while the platform pins Jackson 2 ({@code com.fasterxml}). Carrying them as
 * {@code Object} sidesteps the version split — the same approach loyalty-earning uses for its DSL.
 */
public final class RedemptionDtos {

    private RedemptionDtos() {
    }

    // --- redemptions ---------------------------------------------------------

    public record RedemptionRequest(Long memberId, Long customerId, String accountNumber, Long programId,
                                    Long rewardId, String stepUpToken) {
    }

    public record RedemptionResponse(Long redemptionId, Long memberId, Long rewardId, String status,
                                     Long ledgerEntryId, String externalRef, Instant createdAt,
                                     Instant updatedAt) {
        public static RedemptionResponse from(RedemptionSaga s) {
            return new RedemptionResponse(s.getSagaId(), s.getMemberId(), s.getRewardId(),
                    s.getStatus().name(), s.getLedgerEntryId(), s.getExternalRef(),
                    s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    // --- reward types --------------------------------------------------------

    public record RewardTypeResponse(String rewardTypeCode, String displayName,
                                     String fulfillmentAdapterClass, Object parameterSchema) {
        public static RewardTypeResponse from(RewardTypeEntity t, ObjectMapper mapper) {
            Object schema = readJsonOrNull(t.getParameterSchema(), mapper);
            return new RewardTypeResponse(t.getCode(), t.getDisplayName(),
                    t.getFulfillmentAdapterClass(), schema);
        }
    }

    // --- rewards -------------------------------------------------------------

    public record RewardCreateRequest(String rewardTypeCode, String name, Long pointCost,
                                      Object fulfillmentParams, Object eligibility, Long inventoryTotal) {
    }

    public record RewardUpdateRequest(String status, Long pointCost, Long inventoryTotal,
                                      String bepApprovalRef) {
    }

    public record RewardResponse(Long programId, Long rewardId, String rewardTypeCode, int rewardRevision,
                                 String name, long pointCost, String status, Long inventoryTotal,
                                 Long inventoryRemaining, Object fulfillmentParams) {
        public static RewardResponse from(Reward r, RewardInventory invOrNull, ObjectMapper mapper) {
            Object params = readJsonOrNull(r.getFulfillmentParams(), mapper);
            Long total = invOrNull == null ? null : invOrNull.getTotal();
            Long remaining = invOrNull == null ? null : invOrNull.getRemaining();
            return new RewardResponse(r.getProgramId(), r.getRewardId(), r.getRewardTypeCode(),
                    r.getRewardRevision(), r.getName(), r.getPointCost(), r.getStatus().name(),
                    total, remaining, params);
        }
    }

    private static Object readJsonOrNull(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, Object.class);   // plain Map/List tree
        } catch (Exception e) {
            throw new IllegalStateException("corrupt JSON column: " + json, e);
        }
    }
}
