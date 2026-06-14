package io.github.batnam.loyalty.redemption.saga;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.redemption.config.RedemptionProperties;
import io.github.batnam.loyalty.redemption.elig.EligibilityDecision;
import io.github.batnam.loyalty.redemption.elig.EligibilityEngine;
import io.github.batnam.loyalty.redemption.elig.EligibilityEngine.MemberSnapshot;
import io.github.batnam.loyalty.redemption.error.RedemptionException;
import io.github.batnam.loyalty.redemption.event.RedemptionCompletedEvent;
import io.github.batnam.loyalty.redemption.event.RedemptionFailedEvent;
import io.github.batnam.loyalty.redemption.fulfil.AdapterRegistry;
import io.github.batnam.loyalty.redemption.fulfil.FulfilmentResult;
import io.github.batnam.loyalty.redemption.fulfil.PartnerOutcome;
import io.github.batnam.loyalty.redemption.fulfil.RewardType;
import io.github.batnam.loyalty.redemption.fulfil.SagaContext;
import io.github.batnam.loyalty.redemption.ledger.LedgerClient;
import io.github.batnam.loyalty.redemption.ledger.LedgerDtos.MemberProjectionResponse;
import io.github.batnam.loyalty.redemption.ledger.LedgerDtos.ReservationResponse;
import io.github.batnam.loyalty.redemption.outbox.OutboxRelay;
import io.github.batnam.loyalty.redemption.reward.RewardView;
import io.github.batnam.loyalty.redemption.reward.Rewards;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.Optional;

/**
 * Redemption Orchestrator (L3 §3.2 / §3.3) — the two-phase Saga. {@link #submit} gates Eligibility,
 * reserves inventory + points, dispatches the {@code FulfillmentAdapter} for the Reward Type, then
 * commits (sync SUCCESS) / parks (async PENDING) / releases (FAILURE). {@link #resume} finishes a parked
 * saga from a partner outcome. The Saga is the only writer of {@code redemption_saga}; adapters only
 * return outcomes.
 *
 * <p>The in-process commit/release HTTP calls to core run inside this transaction — the design choice
 * that keeps the sub-second paths consistent (L3 §3.1). A reject (eligibility / inventory / balance) is
 * surfaced as a {@code 409} before any saga row exists.
 */
@Service
public class RedemptionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RedemptionOrchestrator.class);
    private static final ObjectMapper PARAMS = new ObjectMapper();
    private static final String AGGREGATE = "Redemption";

    private final Rewards rewards;
    private final EligibilityEngine eligibility;
    private final LedgerClient ledger;
    private final AdapterRegistry adapters;
    private final SagaRepository sagas;
    private final RedemptionIdempotencyRepository idempotency;
    private final OutboxRelay outbox;
    private final RedemptionProperties props;

    public RedemptionOrchestrator(Rewards rewards, EligibilityEngine eligibility,
                                  LedgerClient ledger, AdapterRegistry adapters, SagaRepository sagas,
                                  RedemptionIdempotencyRepository idempotency, OutboxRelay outbox,
                                  RedemptionProperties props) {
        this.rewards = rewards;
        this.eligibility = eligibility;
        this.ledger = ledger;
        this.adapters = adapters;
        this.sagas = sagas;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.props = props;
    }

    @Transactional
    public RedemptionResult submit(long memberId, long customerId, String accountNumber, long programId,
                                   long rewardId, String idempotencyKey, String stepUpToken) {
        // Replay short-circuit: a key we've already processed returns its original saga verbatim.
        if (idempotency.existsById(idempotencyKey)) {
            RedemptionSaga original = sagas.findById(
                    idempotency.findById(idempotencyKey).orElseThrow().getSagaId()).orElseThrow();
            return RedemptionResult.of(original, true);
        }

        RewardView reward = rewards.find(rewardId)
                .orElseThrow(() -> RedemptionException.notFound("REWARD_NOT_FOUND", "reward " + rewardId));

        // Eligibility gate — cheap rejects before any balance/inventory is touched.
        MemberProjectionResponse projection = ledger.projection(memberId, programId);
        MemberSnapshot member = new MemberSnapshot(null, null, null, projection.redeemableBalance(), null);
        int priorRedemptions = sagas.countByMemberIdAndRewardIdAndStatus(memberId, rewardId, SagaStatus.COMMITTED);
        EligibilityDecision decision = eligibility.check(reward.active(),
                rewards.eligibilityRulesFor(rewardId), member, reward.pointCost(), priorRedemptions);
        if (!decision.eligible()) {
            throw RedemptionException.conflict(decision.reason().name(),
                    "redemption not eligible: " + decision.reason());
        }

        // Inventory hold (atomic conditional decrement). Compensated on any later failure.
        if (!rewards.tryReserveInventory(rewardId)) {
            throw RedemptionException.conflict("INVENTORY_EXHAUSTED", "reward " + rewardId + " is out of stock");
        }

        // Phase 1 — reserve points in core. core is authoritative on balance (409 => restore inventory).
        ReservationResponse reservation;
        try {
            reservation = ledger.reserve(memberId, programId, reward.pointCost(), rewardId, idempotencyKey);
        } catch (RestClientResponseException e) {
            rewards.restoreInventory(rewardId);
            if (e.getStatusCode().value() == 409) {
                throw RedemptionException.conflict("INSUFFICIENT_BALANCE",
                        "loyalty-core rejected the reservation (insufficient balance)");
            }
            throw e;
        }

        RedemptionSaga saga = sagas.save(RedemptionSaga.reserved(programId, memberId, rewardId,
                reward.rewardTypeCode(), reward.pointCost(), reservation.reservationId()));
        saga.beginFulfilling();

        // Phase 2 — dispatch the adapter and act on its outcome.
        SagaContext ctx = new SagaContext(saga.getSagaId(), programId, memberId, customerId, accountNumber,
                rewardId, reward.pointCost(), parseParams(reward.fulfillmentParams()));
        FulfilmentResult result = adapters.resolve(RewardType.valueOf(reward.rewardTypeCode())).fulfil(ctx);

        switch (SagaDecider.onFulfilment(result, null)) {
            case SagaAction.Commit c -> {
                Long entryId = ledger.commit(reservation.reservationId(), c.outcomeRef());
                saga.commit(entryId, c.outcomeRef());
                emitCompleted(saga);
            }
            case SagaAction.Park p -> saga.awaitResume(p.externalRef());   // park; Resume Consumer finishes it
            case SagaAction.Release r -> {
                ledger.release(reservation.reservationId(), r.ledgerDetail());
                rewards.restoreInventory(rewardId);
                saga.fail(r.failureReason());
                emitFailed(saga, r.failureReason());
            }
        }

        sagas.save(saga);
        idempotency.save(RedemptionIdempotency.of(idempotencyKey, saga.getSagaId()));
        return RedemptionResult.of(saga, false);
    }

    /**
     * Resume a parked (FULFILLING) saga from a partner outcome (L3 §3.3). Looked up by {@code externalRef};
     * a terminal saga is a silent no-op (a duplicate webhook), and an unknown ref is ignored.
     */
    @Transactional
    public void resume(String externalRef, PartnerOutcome outcome) {
        Optional<RedemptionSaga> found = sagas.findByExternalRef(externalRef);
        if (found.isEmpty()) {
            log.debug("resume for unknown externalRef={} — ignoring", externalRef);
            return;
        }
        RedemptionSaga saga = found.get();
        if (saga.getStatus().isTerminal()) {
            log.debug("resume on terminal saga {} ({}) — no-op", saga.getSagaId(), saga.getStatus());
            return;
        }

        Optional<FulfilmentResult> resumed = adapters
                .resolve(RewardType.valueOf(saga.getRewardTypeCode()))
                .resume(externalRef, outcome);
        if (resumed.isEmpty()) {
            log.debug("adapter had nothing to resume for externalRef={}", externalRef);
            return;
        }

        FulfilmentResult result = resumed.get();
        // Correlation ref is the parked externalRef; the decider prefers a fresh adapter ref over it.
        switch (SagaDecider.onFulfilment(result, externalRef)) {
            case SagaAction.Commit c -> {
                Long entryId = ledger.commit(saga.getReservationId(), c.outcomeRef());
                saga.commit(entryId, c.outcomeRef());
                emitCompleted(saga);
            }
            case SagaAction.Release r -> {
                ledger.release(saga.getReservationId(), r.ledgerDetail());
                rewards.restoreInventory(saga.getRewardId());
                saga.fail(r.failureReason());
                emitFailed(saga, r.failureReason());
            }
            case SagaAction.Park p -> saga.awaitResume(p.externalRef());   // partner still pending on resume
        }
        sagas.save(saga);
    }

    private void emitCompleted(RedemptionSaga saga) {
        outbox.enqueue(AGGREGATE, "RedemptionCompleted", props.topics().redemptionCompleted(),
                String.valueOf(saga.getMemberId()), RedemptionCompletedEvent.from(saga));
    }

    private void emitFailed(RedemptionSaga saga, String reason) {
        outbox.enqueue(AGGREGATE, "RedemptionFailed", props.topics().redemptionFailed(),
                String.valueOf(saga.getMemberId()), RedemptionFailedEvent.from(saga, reason));
    }

    private static Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return PARAMS.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("corrupt fulfillment_params JSON", e);
        }
    }
}
