package io.github.batnam.loyalty.redemption.ledger;

import java.time.Instant;

/**
 * Request/response shapes for loyalty-core's Reservation API (loyalty-core.yaml). Mirrors core's
 * {@code ReservationDtos} so the bodies serialize 1:1 — redemption is a client of core, not a sharer of
 * its types.
 */
public final class LedgerDtos {

    private LedgerDtos() {
    }

    /** Body of {@code POST /reservations} (Phase 1). */
    public record ReserveRequest(Long memberId, Long programId, Long points, Long rewardId, Integer ttlSeconds) {
    }

    /** Response of reserve / commit / release ({@code Reservation}). {@code ledgerEntryId} is the Redeemed
     *  entry id, populated only on commit (null for reserve/release). */
    public record ReservationResponse(Long reservationId, Long memberId, Long programId, long points,
                                      String status, String externalRef, Instant expiresAt,
                                      Long ledgerEntryId) {
    }

    /** Body of {@code POST /reservations/{id}/commit} (Phase 2). */
    public record CommitRequest(String externalRef) {
    }

    /** Body of {@code POST /reservations/{id}/release}. */
    public record ReleaseRequest(String reason) {
    }

    /**
     * Response of {@code GET /members/{id}/programs/{pid}/projection} — the read-side balance + tier the
     * Eligibility Engine gates against. v1 core exposes balance + tierCode + status only (no segment /
     * currency / tenure / tier ordinal), so those gates stay dormant until the projection is enriched.
     */
    public record MemberProjectionResponse(Long memberId, Long programId, long redeemableBalance,
                                           long qualifyingBalance, String tierCode, String status,
                                           Instant asOf) {
    }
}
