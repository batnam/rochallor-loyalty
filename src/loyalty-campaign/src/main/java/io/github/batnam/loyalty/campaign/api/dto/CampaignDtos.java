package io.github.batnam.loyalty.campaign.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.campaign.campaign.Campaign;
import io.github.batnam.loyalty.campaign.drawing.Drawing;
import io.github.batnam.loyalty.campaign.drawing.DrawingEntry;
import io.github.batnam.loyalty.campaign.drawing.WinnerRecord;

import java.time.Instant;

/**
 * Request/response records for the loyalty-campaign internal API (loyalty-campaign.yaml).
 *
 * <p>{@code multiplierRule} / {@code targetSegment} / {@code prize} are carried as plain {@code Object}
 * (Map/List trees), not Jackson tree nodes: Spring Boot 4's web layer is Jackson 3 ({@code tools.jackson})
 * while the platform pins Jackson 2 ({@code com.fasterxml}). Carrying open JSON as {@code Object} sidesteps
 * the version split — the same approach loyalty-redemption uses for its open fields.
 */
public final class CampaignDtos {

    private CampaignDtos() {
    }

    // --- campaigns -----------------------------------------------------------

    public record CampaignResponse(Long programId, String programCode, Long campaignId, String name,
                                   String status, Instant startsAt, Instant endsAt, Object multiplierRule,
                                   Object targetSegment) {
        public static CampaignResponse from(Campaign c, ObjectMapper mapper) {
            return new CampaignResponse(c.getProgramId(), c.getProgramCode(), c.getCampaignId(), c.getName(),
                    c.getStatus().name(), c.getStartsAt(), c.getEndsAt(),
                    readJsonOrNull(c.getMultiplierRule(), mapper), readJsonOrNull(c.getTargetSegment(), mapper));
        }
    }

    public record CampaignCreateRequest(String name, Instant startsAt, Instant endsAt, Object multiplierRule,
                                        Object targetSegment) {
    }

    public record CampaignUpdateRequest(String status, String bepApprovalRef) {
    }

    // --- drawings ------------------------------------------------------------

    public record DrawingResponse(Long drawingId, Long campaignId, Object prize, Instant entryWindowStart,
                                  Instant entryWindowEnd, Instant drawAt, String selectionStrategy,
                                  int winnersCount, String status) {
        public static DrawingResponse from(Drawing d, ObjectMapper mapper) {
            return new DrawingResponse(d.getDrawingId(), d.getCampaignId(), readJsonOrNull(d.getPrize(), mapper),
                    d.getEntryWindowStart(), d.getEntryWindowEnd(), d.getDrawAt(),
                    d.getSelectionStrategy().name(), d.getWinnersCount(), d.getStatus().name());
        }
    }

    public record DrawingCreateRequest(Object prize, Instant entryWindowStart, Instant entryWindowEnd,
                                       Instant drawAt, String selectionStrategy, Integer winnersCount) {
    }

    // --- entries (T-13) ------------------------------------------------------

    public record DrawingEntryRequest(Long memberId, Long sagaId, String idempotencyKey, Integer weight) {
    }

    public record DrawingEntryResponse(Long entryId, Long drawingId, Long memberId, Integer weight,
                                       Instant createdAt) {
        public static DrawingEntryResponse from(DrawingEntry e) {
            return new DrawingEntryResponse(e.getEntryId(), e.getDrawingId(), e.getMemberId(),
                    e.getWeight(), e.getCreatedAt());
        }
    }

    // --- winners -------------------------------------------------------------

    public record WinnerRecordResponse(Long drawingId, Long memberId, int winnerIndex, String seedHex,
                                       Instant drawnAt) {
        public static WinnerRecordResponse from(WinnerRecord w) {
            return new WinnerRecordResponse(w.getDrawingId(), w.getMemberId(), w.getWinnerIndex(),
                    w.getSeedHex(), w.getDrawnAt());
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
