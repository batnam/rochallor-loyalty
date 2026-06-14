package io.github.batnam.loyalty.core.api;

import io.github.batnam.loyalty.core.api.dto.EarnRequest;
import io.github.batnam.loyalty.core.api.dto.LedgerEntryResponse;
import io.github.batnam.loyalty.core.ledger.AppendResult;
import io.github.batnam.loyalty.core.ledger.LedgerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ledger API (loyalty-core.yaml, tag Ledger). Internal-only — called by {@code loyalty-earning} on
 * every matching rule fire. {@code 201} on a fresh write, {@code 200} on an idempotent replay.
 */
@RestController
@RequestMapping("/ledger")
public class LedgerController {

    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/earn")
    public ResponseEntity<LedgerEntryResponse> earn(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody EarnRequest req) {
        AppendResult result = ledger.appendEarn(
                req.memberId(), req.programId(), req.sourceRef(),
                req.qualifyingDelta() != null ? req.qualifyingDelta() : 0,
                req.redeemableDelta() != null ? req.redeemableDelta() : 0,
                req.earnSourceCode(), req.currency(), req.occurredAt());
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(LedgerEntryResponse.from(result.entry()));
    }
}
