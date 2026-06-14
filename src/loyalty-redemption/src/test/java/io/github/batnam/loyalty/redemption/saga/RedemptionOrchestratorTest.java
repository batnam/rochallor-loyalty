package io.github.batnam.loyalty.redemption.saga;

import io.github.batnam.loyalty.redemption.config.RedemptionProperties;
import io.github.batnam.loyalty.redemption.elig.EligibilityEngine;
import io.github.batnam.loyalty.redemption.elig.EligibilityRules;
import io.github.batnam.loyalty.redemption.error.RedemptionException;
import io.github.batnam.loyalty.redemption.fulfil.AdapterRegistry;
import io.github.batnam.loyalty.redemption.fulfil.FulfilmentResult;
import io.github.batnam.loyalty.redemption.fulfil.FulfillmentAdapter;
import io.github.batnam.loyalty.redemption.fulfil.PartnerOutcome;
import io.github.batnam.loyalty.redemption.fulfil.RewardType;
import io.github.batnam.loyalty.redemption.ledger.LedgerClient;
import io.github.batnam.loyalty.redemption.ledger.LedgerDtos.MemberProjectionResponse;
import io.github.batnam.loyalty.redemption.ledger.LedgerDtos.ReservationResponse;
import io.github.batnam.loyalty.redemption.reward.RewardView;
import io.github.batnam.loyalty.redemption.reward.Rewards;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link RedemptionOrchestrator} with mocked I/O (the {@link Rewards} catalogue port,
 * ledger client, adapter registry, saga + idempotency repos, outbox) and the REAL pure
 * {@link EligibilityEngine}. Pins the two-phase contract: eligibility gates before any reserve; sync
 * SUCCESS reserves→commits→emits Completed; FAILURE releases→fails→emits Failed; PENDING parks at
 * FULFILLING (no commit, no event); an idempotent submit replays the original; and the async resume
 * commits / releases by partner outcome, with a terminal saga resume being a silent no-op.
 *
 * <p>Since  the orchestrator resolves the Reward through the {@code Rewards} port (a pure
 * {@link RewardView}), so the test mocks the port rather than the JPA-backed catalogue.
 */
class RedemptionOrchestratorTest {

    private static final long PROGRAM = 1L;
    private static final long MEMBER = 42L;
    private static final long CUSTOMER = 42L;
    private static final String ACCOUNT = "0011000123";
    private static final long REWARD = 7L;
    private static final long COST = 5000L;
    private static final long RESERVATION = 555L;

    private final Rewards rewards = mock(Rewards.class);
    private final LedgerClient ledger = mock(LedgerClient.class);
    private final AdapterRegistry registry = mock(AdapterRegistry.class);
    private final FulfillmentAdapter adapter = mock(FulfillmentAdapter.class);
    private final SagaRepository sagas = mock(SagaRepository.class);
    private final RedemptionIdempotencyRepository idem = mock(RedemptionIdempotencyRepository.class);
    private final io.github.batnam.loyalty.redemption.outbox.OutboxRelay outbox =
            mock(io.github.batnam.loyalty.redemption.outbox.OutboxRelay.class);

    private final RedemptionProperties props = new RedemptionProperties(
            new RedemptionProperties.Topics("loyalty.fulfillment.resume.v1",
                    "loyalty.redemption.completed.v1", "loyalty.redemption.failed.v1"),
            PROGRAM, new RedemptionProperties.Core("http://core"),
            new RedemptionProperties.PaymentHub("http://ph"),
            new RedemptionProperties.VoucherPartner("http://vp"),
            new RedemptionProperties.Campaign("http://camp"),
            900, new RedemptionProperties.Outbox(100));

    private final RedemptionOrchestrator orchestrator = new RedemptionOrchestrator(
            rewards, new EligibilityEngine(), ledger, registry, sagas, idem, outbox, props);

    private RewardView cashbackReward() {
        return new RewardView(REWARD, PROGRAM, "CASHBACK", COST, "{\"amount\":50000}", true);
    }

    private void wireHappyDeps(FulfilmentResult adapterResult) {
        when(idem.existsById(anyString())).thenReturn(false);
        when(rewards.find(REWARD)).thenReturn(Optional.of(cashbackReward()));
        when(rewards.eligibilityRulesFor(REWARD)).thenReturn(EligibilityRules.NONE);
        when(rewards.tryReserveInventory(REWARD)).thenReturn(true);
        when(ledger.projection(MEMBER, PROGRAM)).thenReturn(
                new MemberProjectionResponse(MEMBER, PROGRAM, 100_000, 0, "GOLD", "ACTIVE", Instant.now()));
        when(sagas.countByMemberIdAndRewardIdAndStatus(MEMBER, REWARD, SagaStatus.COMMITTED)).thenReturn(0);
        when(ledger.reserve(eq(MEMBER), eq(PROGRAM), eq(COST), eq(REWARD), anyString())).thenReturn(
                new ReservationResponse(RESERVATION, MEMBER, PROGRAM, COST, "HELD", null, Instant.now(), null));
        when(sagas.save(any(RedemptionSaga.class))).thenAnswer(i -> i.getArgument(0));
        when(registry.resolve(RewardType.CASHBACK)).thenReturn(adapter);
        when(adapter.fulfil(any())).thenReturn(adapterResult);
    }

    @Test
    void eligibilityRejectShortCircuitsBeforeReserve() {
        when(idem.existsById(anyString())).thenReturn(false);
        when(rewards.find(REWARD)).thenReturn(Optional.of(cashbackReward()));
        when(rewards.eligibilityRulesFor(REWARD)).thenReturn(EligibilityRules.NONE);
        when(sagas.countByMemberIdAndRewardIdAndStatus(MEMBER, REWARD, SagaStatus.COMMITTED)).thenReturn(0);
        when(ledger.projection(MEMBER, PROGRAM)).thenReturn(   // balance below cost
                new MemberProjectionResponse(MEMBER, PROGRAM, 100, 0, "GOLD", "ACTIVE", Instant.now()));

        assertThatThrownBy(() -> orchestrator.submit(MEMBER, CUSTOMER, ACCOUNT, PROGRAM, REWARD, "idem-1", null))
                .isInstanceOf(RedemptionException.class)
                .hasMessageContaining("INSUFFICIENT_BALANCE");

        verify(ledger, never()).reserve(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(rewards, never()).tryReserveInventory(anyLong());
        verify(sagas, never()).save(any());
    }

    @Test
    void inventoryExhaustionShortCircuitsBeforeReserve() {
        when(idem.existsById(anyString())).thenReturn(false);
        when(rewards.find(REWARD)).thenReturn(Optional.of(cashbackReward()));
        when(rewards.eligibilityRulesFor(REWARD)).thenReturn(EligibilityRules.NONE);
        when(sagas.countByMemberIdAndRewardIdAndStatus(MEMBER, REWARD, SagaStatus.COMMITTED)).thenReturn(0);
        when(ledger.projection(MEMBER, PROGRAM)).thenReturn(
                new MemberProjectionResponse(MEMBER, PROGRAM, 100_000, 0, "GOLD", "ACTIVE", Instant.now()));
        when(rewards.tryReserveInventory(REWARD)).thenReturn(false);

        assertThatThrownBy(() -> orchestrator.submit(MEMBER, CUSTOMER, ACCOUNT, PROGRAM, REWARD, "idem-x", null))
                .isInstanceOf(RedemptionException.class)
                .hasFieldOrPropertyWithValue("code", "INVENTORY_EXHAUSTED");
        verify(ledger, never()).reserve(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void syncSuccessReservesCommitsAndEmitsCompleted() {
        wireHappyDeps(FulfilmentResult.success("PH-TXN-1"));
        when(ledger.commit(RESERVATION, "PH-TXN-1")).thenReturn(900L);

        RedemptionResult result = orchestrator.submit(MEMBER, CUSTOMER, ACCOUNT, PROGRAM, REWARD, "idem-2", null);

        assertThat(result.status()).isEqualTo(SagaStatus.COMMITTED);
        assertThat(result.ledgerEntryId()).isEqualTo(900L);
        assertThat(result.replayed()).isFalse();
        verify(ledger).reserve(MEMBER, PROGRAM, COST, REWARD, "idem-2");
        verify(ledger).commit(RESERVATION, "PH-TXN-1");
        verify(ledger, never()).release(anyLong(), anyString());
        verify(outbox).enqueue(anyString(), eq("RedemptionCompleted"),
                eq("loyalty.redemption.completed.v1"), eq(String.valueOf(MEMBER)), any());
        verify(idem).save(any(RedemptionIdempotency.class));
    }

    @Test
    void adapterFailureReleasesAndEmitsFailed() {
        wireHappyDeps(FulfilmentResult.failure("payment hub declined"));

        RedemptionResult result = orchestrator.submit(MEMBER, CUSTOMER, ACCOUNT, PROGRAM, REWARD, "idem-3", null);

        assertThat(result.status()).isEqualTo(SagaStatus.FAILED);
        verify(ledger).release(eq(RESERVATION), anyString());
        verify(ledger, never()).commit(anyLong(), anyString());
        verify(rewards).restoreInventory(REWARD);
        verify(outbox).enqueue(anyString(), eq("RedemptionFailed"),
                eq("loyalty.redemption.failed.v1"), eq(String.valueOf(MEMBER)), any());
        verify(idem).save(any(RedemptionIdempotency.class));
    }

    @Test
    void asyncPendingParksAtFulfillingWithNoCommitOrEvent() {
        wireHappyDeps(FulfilmentResult.pending("VOUCHER-REF-1"));

        RedemptionResult result = orchestrator.submit(MEMBER, CUSTOMER, ACCOUNT, PROGRAM, REWARD, "idem-4", null);

        assertThat(result.status()).isEqualTo(SagaStatus.FULFILLING);
        assertThat(result.externalRef()).isEqualTo("VOUCHER-REF-1");
        verify(ledger, never()).commit(anyLong(), anyString());
        verify(ledger, never()).release(anyLong(), anyString());
        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), anyString(), any());
        verify(idem).save(any(RedemptionIdempotency.class));   // submit WAS processed
    }

    @Test
    void idempotentReplayReturnsOriginalWithoutReReserving() {
        RedemptionSaga existing = RedemptionSaga.reserved(PROGRAM, MEMBER, REWARD, "CASHBACK", COST, RESERVATION);
        existing.beginFulfilling();
        existing.commit(900L, "PH-TXN-1");
        when(idem.existsById("idem-dup")).thenReturn(true);
        when(idem.findById("idem-dup")).thenReturn(Optional.of(
                RedemptionIdempotency.of("idem-dup", 123L)));
        when(sagas.findById(123L)).thenReturn(Optional.of(existing));

        RedemptionResult result = orchestrator.submit(MEMBER, CUSTOMER, ACCOUNT, PROGRAM, REWARD, "idem-dup", null);

        assertThat(result.replayed()).isTrue();
        assertThat(result.status()).isEqualTo(SagaStatus.COMMITTED);
        verify(ledger, never()).reserve(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(rewards, never()).find(anyLong());
    }

    @Test
    void resumeSuccessCommitsAndEmitsCompleted() {
        RedemptionSaga parked = RedemptionSaga.reserved(PROGRAM, MEMBER, REWARD, "THIRD_PARTY_VOUCHER", COST, RESERVATION);
        parked.beginFulfilling();
        parked.awaitResume("VOUCHER-REF-1");
        when(sagas.findByExternalRef("VOUCHER-REF-1")).thenReturn(Optional.of(parked));
        when(sagas.save(any(RedemptionSaga.class))).thenAnswer(i -> i.getArgument(0));
        when(registry.resolve(RewardType.THIRD_PARTY_VOUCHER)).thenReturn(adapter);
        when(adapter.resume(eq("VOUCHER-REF-1"), any()))
                .thenReturn(Optional.of(FulfilmentResult.success("VOUCHER-REF-1")));
        when(ledger.commit(RESERVATION, "VOUCHER-REF-1")).thenReturn(901L);

        orchestrator.resume("VOUCHER-REF-1", new PartnerOutcome(true, "VOUCHER-REF-1", null));

        assertThat(parked.getStatus()).isEqualTo(SagaStatus.COMMITTED);
        verify(ledger).commit(RESERVATION, "VOUCHER-REF-1");
        verify(outbox).enqueue(anyString(), eq("RedemptionCompleted"), anyString(), anyString(), any());
    }

    @Test
    void resumeFailureReleasesAndEmitsFailed() {
        RedemptionSaga parked = RedemptionSaga.reserved(PROGRAM, MEMBER, REWARD, "THIRD_PARTY_VOUCHER", COST, RESERVATION);
        parked.beginFulfilling();
        parked.awaitResume("VOUCHER-REF-2");
        when(sagas.findByExternalRef("VOUCHER-REF-2")).thenReturn(Optional.of(parked));
        when(sagas.save(any(RedemptionSaga.class))).thenAnswer(i -> i.getArgument(0));
        when(registry.resolve(RewardType.THIRD_PARTY_VOUCHER)).thenReturn(adapter);
        when(adapter.resume(eq("VOUCHER-REF-2"), any()))
                .thenReturn(Optional.of(FulfilmentResult.failure("partner could not provision")));

        orchestrator.resume("VOUCHER-REF-2", new PartnerOutcome(false, "VOUCHER-REF-2", null));

        assertThat(parked.getStatus()).isEqualTo(SagaStatus.FAILED);
        verify(ledger).release(eq(RESERVATION), anyString());
        verify(rewards).restoreInventory(REWARD);
        verify(outbox).enqueue(anyString(), eq("RedemptionFailed"), anyString(), anyString(), any());
    }

    @Test
    void resumeOnTerminalSagaIsSilentNoOp() {
        RedemptionSaga done = RedemptionSaga.reserved(PROGRAM, MEMBER, REWARD, "THIRD_PARTY_VOUCHER", COST, RESERVATION);
        done.beginFulfilling();
        done.commit(900L, "VOUCHER-REF-3");
        when(sagas.findByExternalRef("VOUCHER-REF-3")).thenReturn(Optional.of(done));

        orchestrator.resume("VOUCHER-REF-3", new PartnerOutcome(true, "VOUCHER-REF-3", null));

        verify(ledger, never()).commit(anyLong(), anyString());
        verify(ledger, never()).release(anyLong(), anyString());
        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void resumeForUnknownExternalRefIsIgnored() {
        when(sagas.findByExternalRef("nope")).thenReturn(Optional.empty());
        orchestrator.resume("nope", new PartnerOutcome(true, "nope", null));
        verify(ledger, never()).commit(anyLong(), anyString());
    }
}
