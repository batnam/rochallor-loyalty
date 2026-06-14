package io.github.batnam.loyalty.mobilebff.client;

import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.CampaignSummary;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.DrawingEntry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Anti-Corruption boundary to loyalty-campaign: the live campaigns a member is eligible for, and the
 * member's own sweepstakes entries + winner status. Entering a sweepstakes is performed via
 * {@code POST /redemptions} with a sweepstakes-entry reward (Arch §4.6.5), not here.
 */
@Component
public class CampaignClient {

    private final RestClient campaign;

    public CampaignClient(@Qualifier("campaignRestClient") RestClient campaignRestClient) {
        this.campaign = campaignRestClient;
    }

    public List<CampaignSummary> listLiveCampaigns(long programId) {
        return campaign.get()
                .uri(uri -> uri.path("/programs/{programId}/campaigns").queryParam("status", "LIVE").build(programId))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public List<DrawingEntry> listMyDrawingEntries(long memberId, long programId) {
        return campaign.get()
                .uri("/members/{memberId}/programs/{programId}/drawing-entries", memberId, programId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }
}
