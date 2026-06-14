package io.github.batnam.loyalty.campaign.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.campaign.api.dto.CampaignDtos.CampaignCreateRequest;
import io.github.batnam.loyalty.campaign.api.dto.CampaignDtos.CampaignResponse;
import io.github.batnam.loyalty.campaign.api.dto.CampaignDtos.CampaignUpdateRequest;
import io.github.batnam.loyalty.campaign.campaign.Campaign;
import io.github.batnam.loyalty.campaign.campaign.CampaignService;
import io.github.batnam.loyalty.campaign.campaign.CampaignStatus;
import io.github.batnam.loyalty.campaign.error.CampaignException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Campaigns API (loyalty-campaign.yaml, tag Campaigns). Active-list reads serve both BFFs; the writes
 * (create DRAFT, transition) are approval-gated in {@link CampaignService} and hash-chain audited. The
 * {@code X-Actor} header carries the BEP operator identity for the audit trail (default {@code admin-bff}).
 */
@RestController
public class CampaignController {

    private final CampaignService campaigns;
    private final ObjectMapper mapper;

    public CampaignController(CampaignService campaigns, ObjectMapper appObjectMapper) {
        this.campaigns = campaigns;
        this.mapper = appObjectMapper;
    }

    @GetMapping("/programs/{programId}/campaigns")
    public List<CampaignResponse> list(@PathVariable long programId,
                                       @RequestParam(required = false) CampaignStatus status) {
        return campaigns.listByProgram(programId, status).stream()
                .map(c -> CampaignResponse.from(c, mapper)).toList();
    }

    @PostMapping("/programs/{programId}/campaigns")
    public ResponseEntity<CampaignResponse> create(@PathVariable long programId,
                                                   @RequestHeader(value = "X-Actor", defaultValue = "admin-bff") String actor,
                                                   @RequestBody CampaignCreateRequest req) {
        Campaign created = campaigns.create(actor, programId, req.name(), req.startsAt(), req.endsAt(),
                req.multiplierRule(), req.targetSegment());
        return ResponseEntity.status(HttpStatus.CREATED).body(CampaignResponse.from(created, mapper));
    }

    @GetMapping("/campaigns/{campaignId}")
    public CampaignResponse get(@PathVariable long campaignId) {
        return CampaignResponse.from(campaigns.get(campaignId), mapper);
    }

    @PatchMapping("/campaigns/{campaignId}")
    public CampaignResponse update(@PathVariable long campaignId,
                                   @RequestHeader(value = "X-Actor", defaultValue = "admin-bff") String actor,
                                   @RequestBody CampaignUpdateRequest req) {
        CampaignStatus target = parseStatus(req.status());
        Campaign updated = campaigns.transition(actor, campaignId, target, req.bepApprovalRef());
        return CampaignResponse.from(updated, mapper);
    }

    private static CampaignStatus parseStatus(String raw) {
        if (raw == null) {
            throw CampaignException.badRequest("BAD_STATUS", "status is required");
        }
        try {
            return CampaignStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw CampaignException.badRequest("BAD_STATUS", "unknown campaign status " + raw);
        }
    }
}
