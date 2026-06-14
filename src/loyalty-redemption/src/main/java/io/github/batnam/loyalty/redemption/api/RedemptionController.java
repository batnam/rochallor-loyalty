package io.github.batnam.loyalty.redemption.api;

import io.github.batnam.loyalty.redemption.api.dto.RedemptionDtos.RedemptionRequest;
import io.github.batnam.loyalty.redemption.api.dto.RedemptionDtos.RedemptionResponse;
import io.github.batnam.loyalty.redemption.error.RedemptionException;
import io.github.batnam.loyalty.redemption.saga.RedemptionResult;
import io.github.batnam.loyalty.redemption.saga.RedemptionOrchestrator;
import io.github.batnam.loyalty.redemption.saga.SagaRepository;
import io.github.batnam.loyalty.redemption.saga.SagaStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redemptions API (loyalty-redemption.yaml, tag Redemptions). Internal-only — the submit-and-poll Saga
 * entry called by {@code loyalty-mobile-bff}. A synchronous commit returns 200; a partner hand-off
 * (FULFILLING) returns 202 and the client polls {@code GET /redemptions/{id}}. Idempotency-Key replays
 * return the original outcome.
 */
@RestController
@RequestMapping("/redemptions")
public class RedemptionController {

    private final RedemptionOrchestrator orchestrator;
    private final SagaRepository sagas;

    public RedemptionController(RedemptionOrchestrator orchestrator, SagaRepository sagas) {
        this.orchestrator = orchestrator;
        this.sagas = sagas;
    }

    @PostMapping
    public ResponseEntity<RedemptionResponse> submit(
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @RequestBody RedemptionRequest req) {
        RedemptionResult result = orchestrator.submit(
                req.memberId(), req.customerId(), req.accountNumber(), req.programId(), req.rewardId(),
                idempotencyKey, req.stepUpToken());
        RedemptionResponse body = RedemptionResponse.from(
                sagas.findById(result.sagaId()).orElseThrow());
        HttpStatus status = result.status() == SagaStatus.FULFILLING ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/{redemptionId}")
    public RedemptionResponse get(@PathVariable long redemptionId) {
        return RedemptionResponse.from(sagas.findById(redemptionId)
                .orElseThrow(() -> RedemptionException.notFound("REDEMPTION_NOT_FOUND",
                        "redemption " + redemptionId)));
    }
}
