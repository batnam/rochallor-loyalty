package io.github.batnam.loyalty.adminbff.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Request/response records for the BEP edge API (loyalty-admin-bff.yaml). These are the shapes the BEP
 * sees; the {@code client} package maps upstream responses into them (mostly pass-through, since the
 * admin BFF is a role-gated facade over the backend services).
 *
 * <p>Free-form JSON bodies ({@code payload}, {@code dslJson}, {@code fulfillmentParams},
 * {@code parameterSchema}, {@code eligibility}, {@code targetSegment}, {@code prize}) are carried as plain
 * {@code Object} (Map/List trees), not Jackson tree nodes: Spring Boot 4's web layer is Jackson 3
 * ({@code tools.jackson}) while the platform pins Jackson 2 ({@code com.fasterxml}); {@code Object}
 * sidesteps the version split — the same approach the backend services use.
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    // --- members -------------------------------------------------------------

    public record MemberSummary(Long memberId, Long customerId, Instant createdAt) {
    }

    public record MemberProgram(Long programId, String programCode, String status,
                                Long redeemableBalance, Long qualifyingBalance, Integer tcsVersionAccepted) {
    }

    public record MemberDetail(Long memberId, Long customerId, Instant createdAt,
                               List<MemberProgram> programs) {
    }

    public record AdminLedgerEntry(Long programId, String programCode, Long entryId, String entryType,
                                   Long qualifyingDelta, Long redeemableDelta, String sourceRef,
                                   String makerUserId, String checkerUserId, String reason,
                                   Instant createdAt) {
    }

    public record LedgerPage(List<AdminLedgerEntry> items, String nextCursor) {
    }

    // --- approvals -----------------------------------------------------------

    public record ApprovalCreateRequest(String type, Object payload) {
    }

    public record ApprovalConfirmRequest(String decision, String bepApprovalRef, String rejectionReason) {
    }

    public record ApprovalRequest(Long requestId, String type, Object payload, String status,
                                  String requestedBy, String bepApprovalRef, Long appliedRef,
                                  Instant createdAt, Instant appliedAt) {
    }

    // --- earning rules -------------------------------------------------------

    public record EarnSource(Long earnSourceId, String earnSourceCode, String displayName,
                             Boolean activeByDefault) {
    }

    public record RuleCreateRequest(Long earnSourceId, Object dslJson, Instant effectiveFrom,
                                    Instant effectiveTo, Long campaignId) {
    }

    public record RuleDryRunRequest(String eventReplayWindow) {
    }

    public record DryRunResult(Integer matchedEvents, Long totalQualifying, Long totalRedeemable) {
    }

    public record RuleStatusRequest(String status) {
    }

    public record EarningRule(Long programId, String programCode, Long ruleId, Long earnSourceId,
                              Object dslJson, Integer version, String status, Instant effectiveFrom,
                              Instant effectiveTo, Long campaignId) {
    }

    // --- rewards -------------------------------------------------------------

    public record RewardType(String rewardTypeCode, String displayName, String fulfillmentAdapterClass,
                             Object parameterSchema) {
    }

    public record RewardCreateRequest(String rewardTypeCode, String name, Long pointCost,
                                      Object fulfillmentParams, Object eligibility, Long inventoryTotal) {
    }

    public record RewardStatusRequest(String status) {
    }

    public record AdminReward(Long programId, String programCode, Long rewardId, String rewardTypeCode,
                              Integer rewardRevision, String name, Long pointCost, String status,
                              Long inventoryTotal, Long inventoryRemaining) {
    }

    // --- campaigns -----------------------------------------------------------

    public record CampaignCreateRequest(String name, Instant startsAt, Instant endsAt,
                                        Object targetSegment) {
    }

    public record CampaignStatusRequest(String status) {
    }

    public record Campaign(Long programId, String programCode, Long campaignId, String name,
                           Instant startsAt, Instant endsAt, String status) {
    }

    public record DrawingCreateRequest(Instant scheduledAt, String selectionStrategy, Object prize,
                                       Instant entryWindowStart, Instant entryWindowEnd,
                                       Boolean allowMultipleEntries) {
    }

    public record Drawing(Long drawingId, Long campaignId, Instant scheduledAt, String selectionStrategy,
                          String status) {
    }

    public record WinnerRecord(Long winnerId, Long drawingId, Long memberId, String seedHex,
                               Long winnerIndex, Instant drawnAt) {
    }

    // --- fraud ---------------------------------------------------------------

    public record FraudAlert(Long programId, String programCode, Long memberId, String anomalyType,
                             Double observedRate, Double threshold, Instant detectedAt) {
    }

    public record FraudAlertPage(List<FraudAlert> items, String nextCursor) {
    }
}
