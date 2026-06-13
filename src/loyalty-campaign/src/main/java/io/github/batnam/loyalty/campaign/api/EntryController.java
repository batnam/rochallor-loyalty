package io.github.batnam.loyalty.campaign.api;

import io.github.batnam.loyalty.campaign.api.dto.CampaignDtos.DrawingEntryRequest;
import io.github.batnam.loyalty.campaign.api.dto.CampaignDtos.DrawingEntryResponse;
import io.github.batnam.loyalty.campaign.drawing.EntryService;
import io.github.batnam.loyalty.campaign.drawing.EntryService.RecordResult;
import io.github.batnam.loyalty.campaign.error.CampaignException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entries API (loyalty-campaign.yaml, tag Entries / T-13). The single T-13 surface: called by
 * {@code loyalty-redemption}'s SweepstakesAdapter inside a Member's redemption Saga. Idempotent on
 * {@code idempotencyKey} — a fresh entry returns 201, an idempotent replay 200; a closed Drawing or a
 * passed window is a 409 {@code DRAWING_CLOSED}.
 */
@RestController
public class EntryController {

    private final EntryService entries;

    public EntryController(EntryService entries) {
        this.entries = entries;
    }

    @PostMapping("/drawings/{drawingId}/entries")
    public ResponseEntity<DrawingEntryResponse> record(@PathVariable long drawingId,
                                                       @RequestBody DrawingEntryRequest req) {
        if (req.memberId() == null || req.idempotencyKey() == null || req.idempotencyKey().isBlank()) {
            throw CampaignException.badRequest("BAD_ENTRY", "memberId and idempotencyKey are required");
        }
        RecordResult result = entries.recordEntry(drawingId, req.memberId(), req.sagaId(),
                req.idempotencyKey(), req.weight());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(DrawingEntryResponse.from(result.entry()));
    }
}
