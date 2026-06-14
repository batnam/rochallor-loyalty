package io.github.batnam.loyalty.core.api;

import io.github.batnam.loyalty.core.api.dto.ReservationDtos.CommitRequest;
import io.github.batnam.loyalty.core.api.dto.ReservationDtos.ReleaseRequest;
import io.github.batnam.loyalty.core.api.dto.ReservationDtos.ReservationRequest;
import io.github.batnam.loyalty.core.api.dto.ReservationDtos.ReservationResponse;
import io.github.batnam.loyalty.core.domain.reservation.Reservation;
import io.github.batnam.loyalty.core.reservation.ReservationManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reservation API (loyalty-core.yaml, tag Reservations). Internal-only — the two-phase redemption
 * flow called by {@code loyalty-redemption}'s Saga: reserve → commit / release.
 */
@RestController
@RequestMapping("/reservations")
public class ReservationController {

    private final ReservationManager reservations;

    public ReservationController(ReservationManager reservations) {
        this.reservations = reservations;
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ReservationRequest req) {
        Reservation r = reservations.reserve(
                req.memberId(), req.programId(), req.points(), req.rewardId(), req.ttlSeconds(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ReservationResponse.from(r));
    }

    @PostMapping("/{reservationId}/commit")
    public ReservationResponse commit(@PathVariable long reservationId, @RequestBody CommitRequest req) {
        var committed = reservations.commit(reservationId, req.externalRef());
        return ReservationResponse.from(committed.reservation(), committed.ledgerEntryId());
    }

    @PostMapping("/{reservationId}/release")
    public ReservationResponse release(@PathVariable long reservationId,
                                       @RequestBody(required = false) ReleaseRequest req) {
        String reason = req != null ? req.reason() : null;
        return ReservationResponse.from(reservations.release(reservationId, reason));
    }
}
