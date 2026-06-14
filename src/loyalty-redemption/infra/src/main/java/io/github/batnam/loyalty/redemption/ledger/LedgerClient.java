package io.github.batnam.loyalty.redemption.ledger;

import io.github.batnam.loyalty.redemption.config.RedemptionProperties;
import io.github.batnam.loyalty.redemption.ledger.LedgerDtos.CommitRequest;
import io.github.batnam.loyalty.redemption.ledger.LedgerDtos.MemberProjectionResponse;
import io.github.batnam.loyalty.redemption.ledger.LedgerDtos.ReleaseRequest;
import io.github.batnam.loyalty.redemption.ledger.LedgerDtos.ReservationResponse;
import io.github.batnam.loyalty.redemption.ledger.LedgerDtos.ReserveRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Ledger Client (L3 §4 component 11) — the Anti-Corruption boundary to loyalty-core's two-phase
 * Reservation API. The Saga calls {@link #reserve} (Phase 1), then {@link #commit} (Phase 2) on
 * fulfilment SUCCESS or {@link #release} on FAILURE. All Ledger semantics belong to core; this is a
 * thin, header-aware REST wrapper. {@code reserve} carries the {@code Idempotency-Key} so a Saga retry
 * re-binds the original reservation instead of double-holding balance.
 */
@Component
public class LedgerClient {

    private final RestClient core;
    private final int defaultTtlSeconds;

    public LedgerClient(@Qualifier("coreRestClient") RestClient coreRestClient, RedemptionProperties props) {
        this.core = coreRestClient;
        this.defaultTtlSeconds = props.reservationTtlSeconds();
    }

    /** Phase 1 — hold {@code points} for the redemption. Idempotent on the key (core returns 200 on replay). */
    public ReservationResponse reserve(long memberId, long programId, long points, long rewardId,
                                       String idempotencyKey) {
        ReservationResponse resp = core.post()
                .uri("/reservations")
                .header("Idempotency-Key", idempotencyKey)
                .body(new ReserveRequest(memberId, programId, points, rewardId, defaultTtlSeconds))
                .retrieve()
                .body(ReservationResponse.class);
        if (resp == null) {
            throw new IllegalStateException("loyalty-core /reservations returned no body for member " + memberId);
        }
        return resp;
    }

    /** Phase 2 — commit the held reservation, writing the immutable Redeemed entry. Returns its id. */
    public Long commit(long reservationId, String externalRef) {
        ReservationResponse resp = core.post()
                .uri("/reservations/{id}/commit", reservationId)
                .body(new CommitRequest(externalRef))
                .retrieve()
                .body(ReservationResponse.class);
        if (resp == null) {
            throw new IllegalStateException("loyalty-core commit returned no body for reservation " + reservationId);
        }
        return resp.ledgerEntryId();
    }

    /** Read-side Member projection (balance + tier) the Eligibility Engine gates against. */
    public MemberProjectionResponse projection(long memberId, long programId) {
        MemberProjectionResponse resp = core.get()
                .uri("/members/{memberId}/programs/{programId}/projection", memberId, programId)
                .retrieve()
                .body(MemberProjectionResponse.class);
        if (resp == null) {
            throw new IllegalStateException("loyalty-core projection returned no body for member " + memberId);
        }
        return resp;
    }

    /** Compensation — release the held reservation; balance is restored, no Ledger entry is written. */
    public void release(long reservationId, String reason) {
        core.post()
                .uri("/reservations/{id}/release", reservationId)
                .body(new ReleaseRequest(reason))
                .retrieve()
                .toBodilessEntity();
    }
}
