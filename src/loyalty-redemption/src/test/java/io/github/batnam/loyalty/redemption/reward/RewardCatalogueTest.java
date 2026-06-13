package io.github.batnam.loyalty.redemption.reward;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.redemption.audit.AuditLogWriter;
import io.github.batnam.loyalty.redemption.error.RedemptionException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the approval gate in {@link RewardCatalogue} (loyalty-redemption.yaml: ACTIVE
 * transition and pointCost changes are approval-gated; ARCHIVE and inventory bumps apply directly).
 * Persistence is mocked — only the gate decision is under test.
 */
class RewardCatalogueTest {

    private final RewardRepository rewards = mock(RewardRepository.class);
    private final RewardEligibilityRepository eligibility = mock(RewardEligibilityRepository.class);
    private final RewardInventoryRepository inventory = mock(RewardInventoryRepository.class);
    private final RewardTypeRepository types = mock(RewardTypeRepository.class);
    private final AuditLogWriter audit = mock(AuditLogWriter.class);

    private final RewardCatalogue catalogue =
            new RewardCatalogue(rewards, eligibility, inventory, types, audit, new ObjectMapper());

    private Reward draftReward() {
        Reward r = Reward.draft(1L, "CASHBACK", "VND 50k", 5000, "{}");
        when(rewards.findById(7L)).thenReturn(Optional.of(r));
        when(rewards.save(any(Reward.class))).thenAnswer(i -> i.getArgument(0));
        return r;
    }

    @Test
    void activatingWithoutApprovalRefIsRejected() {
        draftReward();
        assertThatThrownBy(() ->
                catalogue.updateReward("op", 7L, RewardStatus.ACTIVE, null, null, null))
                .isInstanceOf(RedemptionException.class)
                .hasMessageContaining("approval");
        verify(rewards, never()).save(any());
    }

    @Test
    void activatingWithApprovalRefSucceeds() {
        Reward r = draftReward();
        catalogue.updateReward("op", 7L, RewardStatus.ACTIVE, null, "BEP-APV-1");
        assertThat(r.getStatus()).isEqualTo(RewardStatus.ACTIVE);
        verify(audit).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void changingPointCostWithoutApprovalRefIsRejected() {
        draftReward();
        assertThatThrownBy(() ->
                catalogue.updateReward("op", 7L, null, 9999L, null))
                .isInstanceOf(RedemptionException.class)
                .hasMessageContaining("approval");
    }

    @Test
    void changingPointCostWithApprovalBumpsRevision() {
        Reward r = draftReward();
        int before = r.getRewardRevision();
        catalogue.updateReward("op", 7L, null, 9999L, "BEP-APV-2");
        assertThat(r.getPointCost()).isEqualTo(9999L);
        assertThat(r.getRewardRevision()).isEqualTo(before + 1);
    }

    @Test
    void archivingNeedsNoApprovalRef() {
        Reward r = draftReward();
        catalogue.updateReward("op", 7L, RewardStatus.ARCHIVED, null, null);
        assertThat(r.getStatus()).isEqualTo(RewardStatus.ARCHIVED);
    }

    @Test
    void unknownRewardIs404() {
        when(rewards.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> catalogue.updateReward("op", 404L, RewardStatus.ARCHIVED, null, null))
                .isInstanceOf(RedemptionException.class)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.NOT_FOUND);
    }
}
