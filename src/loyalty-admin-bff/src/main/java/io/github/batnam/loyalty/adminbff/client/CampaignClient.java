package io.github.batnam.loyalty.adminbff.client;

import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.Campaign;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.CampaignCreateRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.CampaignStatusRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.Drawing;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.DrawingCreateRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.WinnerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Anti-Corruption boundary to loyalty-campaign: campaign CRUD + lifecycle transitions, drawing creation,
 * and audit-replayable winner records. campaign hash-chain audits the writes; the BFF forwards the
 * {@code X-Actor} as the BEP operator identity.
 */
@Component
public class CampaignClient {

    private final RestClient campaign;

    public CampaignClient(@Qualifier("campaignRestClient") RestClient campaignRestClient) {
        this.campaign = campaignRestClient;
    }

    public List<Campaign> listCampaigns(long programId) {
        return campaign.get()
                .uri("/programs/{programId}/campaigns", programId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public Campaign createCampaign(String actor, long programId, CampaignCreateRequest req) {
        return campaign.post()
                .uri("/programs/{programId}/campaigns", programId)
                .header("X-Actor", actor)
                .body(req)
                .retrieve()
                .body(Campaign.class);
    }

    public Campaign updateCampaignStatus(String actor, long campaignId, CampaignStatusRequest req) {
        return campaign.patch()
                .uri("/campaigns/{campaignId}", campaignId)
                .header("X-Actor", actor)
                .body(req)
                .retrieve()
                .body(Campaign.class);
    }

    public Drawing createDrawing(String actor, long campaignId, DrawingCreateRequest req) {
        return campaign.post()
                .uri("/campaigns/{campaignId}/drawings", campaignId)
                .header("X-Actor", actor)
                .body(req)
                .retrieve()
                .body(Drawing.class);
    }

    public List<WinnerRecord> listWinners(long drawingId) {
        return campaign.get()
                .uri("/drawings/{drawingId}/winners", drawingId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }
}
