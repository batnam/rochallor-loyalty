package io.github.batnam.loyalty.campaign.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.campaign.audit.AuditLogWriter;
import io.github.batnam.loyalty.campaign.config.CampaignProperties;
import io.github.batnam.loyalty.campaign.error.CampaignException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Campaign approval gate (loyalty-campaign.yaml: the LIVE transition is approval-gated
 * when the Campaign carries earning multipliers — it is economic). Persistence is mocked — only the gate +
 * transition-legality decisions are under test.
 */
class CampaignServiceTest {

    private final CampaignRepository campaigns = mock(CampaignRepository.class);
    private final AuditLogWriter audit = mock(AuditLogWriter.class);
    private final CampaignProperties props = new CampaignProperties(
            new CampaignProperties.Topics("t.completed", "t.winner", "t.void"),
            1L, "RETAIL",
            new CampaignProperties.Selection("secret"),
            new CampaignProperties.Scheduler("0 * * * * *"),
            new CampaignProperties.Outbox(100));

    private final CampaignService service =
            new CampaignService(campaigns, audit, new ObjectMapper(), props);

    private static final Instant T0 = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-30T00:00:00Z");

    private Campaign economicDraft() {
        Campaign c = Campaign.draft(1L, "RETAIL", "Dining x2", T0, T1, "{\"multiplier\":2}", null);
        when(campaigns.findById(7L)).thenReturn(Optional.of(c));
        when(campaigns.save(any(Campaign.class))).thenAnswer(i -> i.getArgument(0));
        return c;
    }

    private Campaign nonEconomicDraft() {
        Campaign c = Campaign.draft(1L, "RETAIL", "Awareness", T0, T1, null, null);
        when(campaigns.findById(8L)).thenReturn(Optional.of(c));
        when(campaigns.save(any(Campaign.class))).thenAnswer(i -> i.getArgument(0));
        return c;
    }

    @Test
    void createPersistsDraftAndAudits() {
        when(campaigns.save(any(Campaign.class))).thenAnswer(i -> i.getArgument(0));
        Campaign created = service.create("op", 1L, "Dining x2", T0, T1, java.util.Map.of("multiplier", 2), null);
        assertThat(created.getStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(created.getProgramCode()).isEqualTo("RETAIL");   // from config default
        verify(audit).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void goingLiveOnEconomicCampaignWithoutApprovalIsRejected() {
        economicDraft();
        assertThatThrownBy(() -> service.transition("op", 7L, CampaignStatus.LIVE, null))
                .isInstanceOf(CampaignException.class)
                .hasFieldOrPropertyWithValue("code", "MISSING_APPROVAL");
        verify(campaigns, never()).save(any());
    }

    @Test
    void goingLiveOnEconomicCampaignWithApprovalSucceeds() {
        Campaign c = economicDraft();
        service.transition("op", 7L, CampaignStatus.LIVE, "BEP-APV-1");
        assertThat(c.getStatus()).isEqualTo(CampaignStatus.LIVE);
        verify(audit).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void goingLiveOnNonEconomicCampaignNeedsNoApproval() {
        Campaign c = nonEconomicDraft();
        service.transition("op", 8L, CampaignStatus.LIVE, null);
        assertThat(c.getStatus()).isEqualTo(CampaignStatus.LIVE);
    }

    @Test
    void illegalTransitionIsRejected() {
        economicDraft();   // DRAFT
        assertThatThrownBy(() -> service.transition("op", 7L, CampaignStatus.ENDED, "BEP-APV-1"))
                .isInstanceOf(CampaignException.class)
                .hasFieldOrPropertyWithValue("code", "ILLEGAL_TRANSITION");
    }

    @Test
    void unknownCampaignIs404() {
        when(campaigns.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transition("op", 404L, CampaignStatus.LIVE, "x"))
                .isInstanceOf(CampaignException.class)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.NOT_FOUND);
    }
}
