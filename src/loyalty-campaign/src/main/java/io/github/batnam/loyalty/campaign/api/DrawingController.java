package io.github.batnam.loyalty.campaign.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.campaign.api.dto.CampaignDtos.DrawingCreateRequest;
import io.github.batnam.loyalty.campaign.api.dto.CampaignDtos.DrawingResponse;
import io.github.batnam.loyalty.campaign.api.dto.CampaignDtos.WinnerRecordResponse;
import io.github.batnam.loyalty.campaign.drawing.Drawing;
import io.github.batnam.loyalty.campaign.drawing.DrawingService;
import io.github.batnam.loyalty.campaign.error.CampaignException;
import io.github.batnam.loyalty.campaign.select.SelectionStrategy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Drawings API (loyalty-campaign.yaml, tag Drawings). Lists/creates Drawings under a Campaign and serves the
 * immutable winners audit view. Winner Selection itself has no inbound REST trigger — it runs on the
 * in-pod Drawing Scheduler.
 */
@RestController
public class DrawingController {

    private final DrawingService drawings;
    private final ObjectMapper mapper;

    public DrawingController(DrawingService drawings, ObjectMapper appObjectMapper) {
        this.drawings = drawings;
        this.mapper = appObjectMapper;
    }

    @GetMapping("/campaigns/{campaignId}/drawings")
    public List<DrawingResponse> listForCampaign(@PathVariable long campaignId) {
        return drawings.listByCampaign(campaignId).stream().map(d -> DrawingResponse.from(d, mapper)).toList();
    }

    @PostMapping("/campaigns/{campaignId}/drawings")
    public ResponseEntity<DrawingResponse> create(@PathVariable long campaignId,
                                                  @RequestHeader(value = "X-Actor", defaultValue = "admin-bff") String actor,
                                                  @RequestBody DrawingCreateRequest req) {
        Drawing created = drawings.create(actor, campaignId, req.prize(), req.entryWindowStart(),
                req.entryWindowEnd(), req.drawAt(), parseStrategy(req.selectionStrategy()),
                req.winnersCount() == null ? 1 : req.winnersCount());
        return ResponseEntity.status(HttpStatus.CREATED).body(DrawingResponse.from(created, mapper));
    }

    @GetMapping("/drawings/{drawingId}")
    public DrawingResponse get(@PathVariable long drawingId) {
        return DrawingResponse.from(drawings.get(drawingId), mapper);
    }

    @GetMapping("/drawings/{drawingId}/winners")
    public List<WinnerRecordResponse> winners(@PathVariable long drawingId) {
        return drawings.listWinners(drawingId).stream().map(WinnerRecordResponse::from).toList();
    }

    private static SelectionStrategy parseStrategy(String raw) {
        if (raw == null) {
            return SelectionStrategy.SEEDED_RNG;   // OpenAPI default
        }
        try {
            return SelectionStrategy.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw CampaignException.badRequest("BAD_STRATEGY", "unknown selection strategy " + raw);
        }
    }
}
