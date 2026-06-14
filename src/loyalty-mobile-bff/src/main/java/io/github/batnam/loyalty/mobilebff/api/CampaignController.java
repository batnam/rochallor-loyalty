package io.github.batnam.loyalty.mobilebff.api;

import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.CampaignSummary;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.DrawingEntry;
import io.github.batnam.loyalty.mobilebff.client.CampaignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Campaigns (loyalty-mobile-bff.yaml). Live campaigns the member is eligible for, and the member's own
 * sweepstakes entries + winner status, aggregated from loyalty-campaign.
 */
@RestController
public class CampaignController {

    private final CampaignClient campaign;

    public CampaignController(CampaignClient campaign) {
        this.campaign = campaign;
    }

    @GetMapping("/me/programs/{programId}/campaigns")
    public List<CampaignSummary> listCampaigns(@PathVariable long programId) {
        return campaign.listLiveCampaigns(programId);
    }

    @GetMapping("/me/programs/{programId}/drawing-entries")
    public List<DrawingEntry> listMyDrawingEntries(@RequestParam long customerId, @PathVariable long programId) {
        return campaign.listMyDrawingEntries(customerId, programId);
    }
}
