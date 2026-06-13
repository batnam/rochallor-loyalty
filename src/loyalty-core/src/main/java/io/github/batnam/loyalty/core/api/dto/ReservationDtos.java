package io.github.batnam.loyalty.core.api.dto;

import io.github.batnam.loyalty.core.domain.reservation.Reservation;
import io.github.batnam.loyalty.core.domain.reservation.ReservationStatus;

import java.time.Instant;

/** Reservation request/response DTOs (loyalty-core.yaml {@code ReservationRequest}/{@code Reservation}). */
public final class ReservationDtos {

    private ReservationDtos() {
    }

    public record ReservationRequest(Long memberId, Long programId, Long points, Long rewardId, Integer ttlSeconds) {
    }

    public record CommitRequest(String externalRef) {
    }

    public record ReleaseRequest(String reason) {
    }

    public record ReservationResponse(
            Long reservationId, Long memberId, Long programId, long points,
            ReservationStatus status, String externalRef, Instant expiresAt, Long ledgerEntryId) {

        public static ReservationResponse from(Reservation r) {
            return from(r, null);
        }

        /** {@code ledgerEntryId} is the Redeemed entry written by commit (null for reserve/release). */
        public static ReservationResponse from(Reservation r, Long ledgerEntryId) {
            return new ReservationResponse(r.reservationId(), r.memberId(), r.programId(),
                    r.points(), r.status(), r.externalRef(), r.heldUntil(), ledgerEntryId);
        }
    }
}
