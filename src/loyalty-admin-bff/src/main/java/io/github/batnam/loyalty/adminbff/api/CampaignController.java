package io.github.batnam.loyalty.adminbff.api;

import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.Campaign;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.CampaignCreateRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.CampaignStatusRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.Drawing;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.DrawingCreateRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.WinnerRecord;
import io.github.batnam.loyalty.adminbff.client.CampaignClient;
import io.github.batnam.loyalty.adminbff.security.EmployeeIdentity;
import io.github.batnam.loyalty.adminbff.security.Roles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Campaigns (loyalty-admin-bff.yaml). Campaign + Drawing CRUD, lifecycle transitions, and winner records,
 * aggregated from loyalty-campaign. Writes require the campaign-manager role.
 */
@RestController
public class CampaignController {

    private final CampaignClient campaigns;

    public CampaignController(CampaignClient campaigns) {
        this.campaigns = campaigns;
    }

    @GetMapping("/programs/{programId}/campaigns")
    public List<Campaign> listCampaigns(EmployeeIdentity caller, @PathVariable long programId) {
        caller.requireAnyRole(Roles.CAMPAIGN_MANAGER, Roles.READONLY);
        return campaigns.listCampaigns(programId);
    }

    @PostMapping("/programs/{programId}/campaigns")
    public ResponseEntity<Campaign> createCampaign(EmployeeIdentity caller, @PathVariable long programId,
                                                   @RequestBody CampaignCreateRequest req) {
        caller.requireAnyRole(Roles.CAMPAIGN_MANAGER);
        return ResponseEntity.status(HttpStatus.CREATED).body(campaigns.createCampaign(caller.userId(), programId, req));
    }

    @PatchMapping("/campaigns/{campaignId}")
    public Campaign updateCampaignStatus(EmployeeIdentity caller, @PathVariable long campaignId,
                                         @RequestBody CampaignStatusRequest req) {
        caller.requireAnyRole(Roles.CAMPAIGN_MANAGER);
        return campaigns.updateCampaignStatus(caller.userId(), campaignId, req);
    }

    @PostMapping("/campaigns/{campaignId}/drawings")
    public ResponseEntity<Drawing> createDrawing(EmployeeIdentity caller, @PathVariable long campaignId,
                                                 @RequestBody DrawingCreateRequest req) {
        caller.requireAnyRole(Roles.CAMPAIGN_MANAGER);
        return ResponseEntity.status(HttpStatus.CREATED).body(campaigns.createDrawing(caller.userId(), campaignId, req));
    }

    @GetMapping("/drawings/{drawingId}/winners")
    public List<WinnerRecord> listWinners(EmployeeIdentity caller, @PathVariable long drawingId) {
        caller.requireAnyRole(Roles.CAMPAIGN_MANAGER, Roles.READONLY);
        return campaigns.listWinners(drawingId);
    }
}
