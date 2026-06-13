package io.github.batnam.loyalty.campaign.drawing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.campaign.audit.AuditLogWriter;
import io.github.batnam.loyalty.campaign.campaign.Campaign;
import io.github.batnam.loyalty.campaign.campaign.CampaignService;
import io.github.batnam.loyalty.campaign.error.CampaignException;
import io.github.batnam.loyalty.campaign.select.SelectionStrategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Drawing Aggregate service (L3 §4 component 3) — Drawing authoring + reads. A Drawing is created OPEN under
 * an existing Campaign (inheriting its {@code programId}); the winners view is the immutable audit list once
 * the Drawing is CLOSED. Winner Selection (the close transition) lives in {@link WinnerSelectionService},
 * driven by the scheduler, not by an API call.
 */
@Service
public class DrawingService {

    private final DrawingRepository drawings;
    private final WinnerRecordRepository winners;
    private final CampaignService campaigns;
    private final AuditLogWriter audit;
    private final ObjectMapper mapper;

    public DrawingService(DrawingRepository drawings, WinnerRecordRepository winners,
                          CampaignService campaigns, AuditLogWriter audit, ObjectMapper appObjectMapper) {
        this.drawings = drawings;
        this.winners = winners;
        this.campaigns = campaigns;
        this.audit = audit;
        this.mapper = appObjectMapper;
    }

    public Drawing get(long drawingId) {
        return drawings.findById(drawingId)
                .orElseThrow(() -> CampaignException.notFound("DRAWING_NOT_FOUND", "drawing " + drawingId));
    }

    public List<Drawing> listByCampaign(long campaignId) {
        return drawings.findByCampaignIdOrderByDrawingIdAsc(campaignId);
    }

    public List<WinnerRecord> listWinners(long drawingId) {
        get(drawingId);   // 404 if the drawing doesn't exist
        return winners.findByDrawingIdOrderByWinnerIndexAsc(drawingId);
    }

    @Transactional
    public Drawing create(String actor, long campaignId, Object prize, Instant entryWindowStart,
                          Instant entryWindowEnd, Instant drawAt, SelectionStrategy strategy, int winnersCount) {
        Campaign campaign = campaigns.get(campaignId);   // 404 if the campaign doesn't exist
        Drawing saved = drawings.save(Drawing.open(campaignId, campaign.getProgramId(), toJson(prize),
                entryWindowStart, entryWindowEnd, drawAt, strategy, winnersCount));
        audit.record(actor, "CREATE", "Drawing", String.valueOf(saved.getDrawingId()), null,
                "{\"campaignId\":" + campaignId + ",\"drawAt\":\"" + drawAt + "\"}");
        return saved;
    }

    private String toJson(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw CampaignException.badRequest("BAD_JSON", "prize not serializable to JSON");
        }
    }
}
