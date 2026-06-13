package io.github.batnam.loyalty.campaign.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.campaign.audit.AuditLogWriter;
import io.github.batnam.loyalty.campaign.config.CampaignProperties;
import io.github.batnam.loyalty.campaign.error.CampaignException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Campaign Aggregate service (L3 §4 component 2) — Campaign CRUD + the approval gate. Authoring creates a
 * DRAFT; the {@code LIVE} transition is approval-gated when the Campaign is economic (carries an earning
 * multiplier) — it requires a {@code bepApprovalRef}, mirroring core/earning/redemption's confirm seam.
 * Every admin write is hash-chain audited. {@code multiplierRule} is stored as JSON and exposed to
 * loyalty-earning, never evaluated here.
 */
@Service
public class CampaignService {

    private final CampaignRepository campaigns;
    private final AuditLogWriter audit;
    private final ObjectMapper mapper;
    private final CampaignProperties props;

    public CampaignService(CampaignRepository campaigns, AuditLogWriter audit, ObjectMapper appObjectMapper,
                           CampaignProperties props) {
        this.campaigns = campaigns;
        this.audit = audit;
        this.mapper = appObjectMapper;
        this.props = props;
    }

    // --- reads ---------------------------------------------------------------

    public Campaign get(long campaignId) {
        return campaigns.findById(campaignId)
                .orElseThrow(() -> CampaignException.notFound("CAMPAIGN_NOT_FOUND", "campaign " + campaignId));
    }

    public List<Campaign> listByProgram(long programId, CampaignStatus statusOrNull) {
        return statusOrNull == null
                ? campaigns.findByProgramIdOrderByCampaignIdAsc(programId)
                : campaigns.findByProgramIdAndStatusOrderByCampaignIdAsc(programId, statusOrNull);
    }

    // --- admin writes --------------------------------------------------------

    @Transactional
    public Campaign create(String actor, long programId, String name, Instant startsAt, Instant endsAt,
                           Object multiplierRule, Object targetSegment) {
        Campaign saved = campaigns.save(Campaign.draft(programId, props.defaultProgramCode(), name,
                startsAt, endsAt, toJson(multiplierRule), toJson(targetSegment)));
        audit.record(actor, "CREATE", "Campaign", String.valueOf(saved.getCampaignId()), null,
                "{\"name\":\"" + name + "\",\"status\":\"DRAFT\"}");
        return saved;
    }

    /**
     * Transition a Campaign. An illegal move (per {@link CampaignStatus#canTransitionTo}) is a 409
     * {@code ILLEGAL_TRANSITION}; a LIVE transition on an economic Campaign without a {@code bepApprovalRef}
     * is a 409 {@code MISSING_APPROVAL}.
     */
    @Transactional
    public Campaign transition(String actor, long campaignId, CampaignStatus target, String bepApprovalRef) {
        Campaign c = get(campaignId);
        String before = "{\"status\":\"" + c.getStatus() + "\"}";

        if (!c.getStatus().canTransitionTo(target)) {
            throw CampaignException.conflict("ILLEGAL_TRANSITION",
                    "campaign " + campaignId + " cannot move " + c.getStatus() + " -> " + target);
        }
        if (target == CampaignStatus.LIVE && c.isEconomic()
                && (bepApprovalRef == null || bepApprovalRef.isBlank())) {
            throw CampaignException.conflict("MISSING_APPROVAL",
                    "going LIVE with an earning multiplier requires a bepApprovalRef (approval reference)");
        }

        c.transitionTo(target);
        Campaign saved = campaigns.save(c);
        String after = "{\"status\":\"" + saved.getStatus() + "\"}";
        audit.record(actor, "TRANSITION", "Campaign", String.valueOf(campaignId), before, after);
        return saved;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw CampaignException.badRequest("BAD_JSON", "field not serializable to JSON");
        }
    }
}
