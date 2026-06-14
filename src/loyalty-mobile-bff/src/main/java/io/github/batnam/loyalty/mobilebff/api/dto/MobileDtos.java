package io.github.batnam.loyalty.mobilebff.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Request/response records for the customer edge API (loyalty-mobile-bff.yaml). These are the shapes the
 * Mobile App sees; the {@code client} package maps upstream responses into them.
 *
 * <p>{@code fulfillmentParams} is carried as plain {@code Object} (Map/List tree), not a Jackson tree
 * node: Spring Boot 4's web layer is Jackson 3 ({@code tools.jackson}) while the platform pins Jackson 2
 * ({@code com.fasterxml}); {@code Object} sidesteps the version split — the same approach the backend
 * services use for their JSON blobs. Every Points-bearing payload carries {@code programId} +
 * {@code programCode} (Multi-Program, Arch §4.2.2).
 */
public final class MobileDtos {

    private MobileDtos() {
    }

    public record TierRef(Long tierId, String name) {
    }

    public record EnrolledProgram(Long programId, String programCode, String optInStatus,
                                  Integer tcsVersionAccepted, String eligibilityStatus,
                                  Long redeemableBalance, Long qualifyingBalance, TierRef tier) {
    }

    public record Balance(Long programId, String programCode, Long redeemableBalance,
                          Long effectiveRedeemableBalance, Long qualifyingBalance) {
    }

    public record LedgerEntry(Long programId, String programCode, Long entryId, String entryType,
                              Long qualifyingDelta, Long redeemableDelta, String reason,
                              Instant createdAt, Instant expiresAt) {
    }

    public record TransactionPage(List<LedgerEntry> items, String nextCursor) {
    }

    public record TierState(Long programId, String programCode, TierRef currentTier, TierRef nextTier,
                            Long qualifyingBalance, Long pointsToNextTier, Float progressPercent) {
    }

    public record ExpiringCohort(Long amount, Instant expiresAt) {
    }

    public record RewardSummary(Long programId, String programCode, Long rewardId, String name,
                                String rewardTypeCode, Long pointCost, Long inventoryRemaining) {
    }

    public record RewardPage(List<RewardSummary> items, String nextCursor) {
    }

    public record Reward(Long programId, String programCode, Long rewardId, String name,
                         String rewardTypeCode, Long pointCost, Long inventoryRemaining,
                         Object fulfillmentParams, Instant validityFrom, Instant validityTo) {
    }

    public record RedemptionResult(Long programId, String programCode, Long redemptionId, Long rewardId,
                                   String status, String externalRef) {
    }

    public record CampaignSummary(Long programId, String programCode, Long campaignId, String name,
                                  Instant startsAt, Instant endsAt) {
    }

    public record DrawingEntry(Long drawingId, Long campaignId, Long entryId, Boolean isWinner,
                               Instant drawnAt) {
    }

    // --- request bodies ------------------------------------------------------

    public record TcsRequest(Long customerId, Integer tcsVersion) {
    }

    /**
     * The Mobile App (a Host Bank app) supplies bank context only: {@code customerId} (the bank's customer
     * identifier — whose points to spend and whose CASA receives a cashback credit) and
     * {@code accountNumber} (the CASA the customer picked; one CIF may own several; null for reward types
     * that don't credit an account). No Loyalty {@code memberId} crosses this boundary — the BFF maps
     * {@code customerId} to the internal member identity (v1 1:1).
     */
    public record RedeemRequest(Long customerId, String accountNumber, Long programId, Long rewardId) {
    }
}
