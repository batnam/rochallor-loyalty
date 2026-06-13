package io.github.batnam.loyalty.campaign.campaign;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Campaign lifecycle (loyalty-campaign.yaml PATCH enum: DRAFT → SCHEDULED → LIVE → ENDED →
 * ARCHIVED). Forward-only; ARCHIVED is terminal. Pure logic, no I/O. (Approval gating for the LIVE
 * transition is a service concern, not encoded here.)
 */
class CampaignStatusTest {

    @Test
    void draftMayScheduleGoLiveOrArchive() {
        assertThat(CampaignStatus.DRAFT.canTransitionTo(CampaignStatus.SCHEDULED)).isTrue();
        assertThat(CampaignStatus.DRAFT.canTransitionTo(CampaignStatus.LIVE)).isTrue();
        assertThat(CampaignStatus.DRAFT.canTransitionTo(CampaignStatus.ARCHIVED)).isTrue();
    }

    @Test
    void scheduledMayGoLiveEndOrArchive() {
        assertThat(CampaignStatus.SCHEDULED.canTransitionTo(CampaignStatus.LIVE)).isTrue();
        assertThat(CampaignStatus.SCHEDULED.canTransitionTo(CampaignStatus.ENDED)).isTrue();
        assertThat(CampaignStatus.SCHEDULED.canTransitionTo(CampaignStatus.ARCHIVED)).isTrue();
    }

    @Test
    void liveMayOnlyEnd() {
        assertThat(CampaignStatus.LIVE.canTransitionTo(CampaignStatus.ENDED)).isTrue();
        assertThat(CampaignStatus.LIVE.canTransitionTo(CampaignStatus.ARCHIVED)).isFalse();
        assertThat(CampaignStatus.LIVE.canTransitionTo(CampaignStatus.DRAFT)).isFalse();
    }

    @Test
    void endedMayOnlyArchive() {
        assertThat(CampaignStatus.ENDED.canTransitionTo(CampaignStatus.ARCHIVED)).isTrue();
        assertThat(CampaignStatus.ENDED.canTransitionTo(CampaignStatus.LIVE)).isFalse();
    }

    @Test
    void cannotSkipStraightFromDraftToEnded() {
        assertThat(CampaignStatus.DRAFT.canTransitionTo(CampaignStatus.ENDED)).isFalse();
    }

    @Test
    void archivedIsTerminal() {
        for (CampaignStatus to : CampaignStatus.values()) {
            assertThat(CampaignStatus.ARCHIVED.canTransitionTo(to))
                    .as("ARCHIVED -> %s must be rejected", to).isFalse();
        }
        assertThat(CampaignStatus.ARCHIVED.isTerminal()).isTrue();
    }

    @Test
    void noStatusTransitionsToItself() {
        for (CampaignStatus s : CampaignStatus.values()) {
            assertThat(s.canTransitionTo(s)).as("%s -> %s", s, s).isFalse();
        }
    }
}
