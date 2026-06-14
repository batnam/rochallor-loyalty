package io.github.batnam.loyalty.mobilebff.client;

import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.Reward;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.RewardPage;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.RewardSummary;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.RedemptionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Anti-Corruption boundary to loyalty-redemption: the eligible reward catalogue and the two-phase
 * redemption Saga. The edge speaks {@code customerId} only; this client maps it to the internal contract
 * (v1 1:1 {@code customerId → memberId}) and carries {@code customerId}/{@code accountNumber} through for the
 * cashback disbursement — the Host Bank Payment Hub is addressed by CIF and account, never by Loyalty's
 * internal {@code memberId}. {@code Idempotency-Key} and the optional step-up token are forwarded
 * verbatim — redemption owns their semantics.
 */
@Component
public class RedemptionClient {

    private final RestClient redemption;

    public RedemptionClient(@Qualifier("redemptionRestClient") RestClient redemptionRestClient) {
        this.redemption = redemptionRestClient;
    }

    public RewardPage listEligibleRewards(long memberId, long programId, String cursor, Integer limit) {
        // redemption returns a bare JSON array of rewards (no server-side paging); the BFF wraps it
        // into the customer-facing RewardPage.
        List<RewardSummary> items = redemption.get()
                .uri(uri -> {
                    uri.path("/programs/{programId}/rewards/eligible").queryParam("memberId", memberId);
                    if (cursor != null) {
                        uri.queryParam("cursor", cursor);
                    }
                    if (limit != null) {
                        uri.queryParam("limit", limit);
                    }
                    return uri.build(programId);
                })
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return new RewardPage(items == null ? List.of() : items, null);
    }

    public Reward getReward(long rewardId) {
        return redemption.get()
                .uri("/rewards/{rewardId}", rewardId)
                .retrieve()
                .body(Reward.class);
    }

    /**
     * Submit a redemption; propagate the upstream status (200 sync / 202 async) to the edge. The edge
     * passes only {@code customerId} (+ target CASA); this ACL maps it to the internal redemption contract —
     * v1's 1:1 {@code customerId → memberId} seam — while {@code customerId} (the CIF) and {@code accountNumber}
     * are what loyalty-redemption hands the Host Bank Payment Hub.
     */
    public RedeemOutcome redeem(long customerId, String accountNumber, long programId, long rewardId,
                                String idempotencyKey, String stepUpToken) {
        ResponseEntity<UpstreamRedemption> resp = redemption.post()
                .uri("/redemptions")
                .header("Idempotency-Key", idempotencyKey)
                .body(new SubmitBody(customerId, customerId, accountNumber, programId, rewardId, stepUpToken))
                .retrieve()
                .toEntity(UpstreamRedemption.class);
        return new RedeemOutcome(resp.getStatusCode().value(), toResult(resp.getBody()));
    }

    public RedemptionResult getRedemption(long redemptionId) {
        return toResult(redemption.get()
                .uri("/redemptions/{redemptionId}", redemptionId)
                .retrieve()
                .body(UpstreamRedemption.class));
    }

    private static RedemptionResult toResult(UpstreamRedemption u) {
        if (u == null) {
            return null;
        }
        return new RedemptionResult(u.programId(), u.programCode(), u.redemptionId(), u.rewardId(),
                mapStatus(u.status()), u.externalRef());
    }

    /** Saga status (loyalty-redemption) → the customer-facing enum (loyalty-mobile-bff.yaml). */
    static String mapStatus(String sagaStatus) {
        if (sagaStatus == null) {
            return "PENDING";
        }
        return switch (sagaStatus) {
            case "COMMITTED" -> "COMPLETED";
            case "RELEASED" -> "FAILED";
            case "RESERVED", "FULFILLING", "FAILED", "PENDING" -> sagaStatus;
            default -> "PENDING";
        };
    }

    public record RedeemOutcome(int statusCode, RedemptionResult result) {
    }

    private record SubmitBody(Long memberId, Long customerId, String accountNumber, Long programId,
                              Long rewardId, String stepUpToken) {
    }

    /** Subset of loyalty-redemption's RedemptionResponse the BFF maps from. */
    private record UpstreamRedemption(Long redemptionId, Long programId, String programCode, Long rewardId,
                                      String status, String externalRef) {
    }
}
