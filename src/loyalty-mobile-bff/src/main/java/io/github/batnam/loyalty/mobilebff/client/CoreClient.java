package io.github.batnam.loyalty.mobilebff.client;

import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.Balance;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.EnrolledProgram;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.ExpiringCohort;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.TierRef;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.TierState;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.TransactionPage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.time.Instant;
import java.util.List;

/**
 * Anti-Corruption boundary to loyalty-core for the customer membership read/write surface: enrolled
 * programs, opt-in/out + T&Cs, balance, transaction history, tier progress, and expiring cohorts.
 *
 * <p>The endpoints below are the member-scoped projections the BFF needs; they extend core's v1 internal
 * API (which today publishes the Reservation API + a slim projection). The {@code memberId} is supplied
 * by the request — the gateway Authentication Service verifies the token and injects the CIF — and is
 * forwarded here; the BFF itself does no token handling.
 */
@Component
public class CoreClient {

    private final RestClient core;

    public CoreClient(@Qualifier("coreRestClient") RestClient coreRestClient) {
        this.core = coreRestClient;
    }

    public List<EnrolledProgram> listEnrolledPrograms(long memberId) {
        return core.get()
                .uri("/members/{memberId}/programs", memberId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public EnrolledProgram optIn(long customerId, long programId, int tcsVersion) {
        // Core's opt-in is POST /members with the customer-scoped body (it mints the memberId).
        CoreMember m = core.post()
                .uri("/members")
                .body(new OptInRequest(programId, customerId, tcsVersion))
                .retrieve()
                .body(CoreMember.class);
        return new EnrolledProgram(m.programId(), null, m.status(), tcsVersion, null,
                m.redeemableBalance(), m.qualifyingBalance(), new TierRef(null, m.tierCode()));
    }

    public void optOut(long memberId, long programId) {
        core.post()
                .uri("/members/{memberId}/programs/{programId}/opt-out", memberId, programId)
                .retrieve()
                .toBodilessEntity();
    }

    public EnrolledProgram acceptTcs(long memberId, long programId, int tcsVersion) {
        return core.post()
                .uri("/members/{memberId}/programs/{programId}/tcs-acceptance", memberId, programId)
                .body(new TcsBody(tcsVersion))
                .retrieve()
                .body(EnrolledProgram.class);
    }

    public Balance getBalance(long memberId, long programId) {
        // Core exposes the read-side as /projection; the BFF shapes it into the customer Balance.
        // Held-reservation netting isn't in the core projection, so effective == redeemable here.
        CoreProjection p = projection(memberId, programId);
        return new Balance(p.programId(), null, p.redeemableBalance(),
                p.redeemableBalance(), p.qualifyingBalance());
    }

    public TransactionPage listTransactions(long memberId, long programId, String cursor, Integer limit) {
        return core.get()
                .uri(uri -> pageUri(uri, "/members/{memberId}/programs/{programId}/transactions",
                        cursor, limit, memberId, programId))
                .retrieve()
                .body(TransactionPage.class);
    }

    public TierState getTier(long memberId, long programId) {
        // Derived from the same core projection (core has no dedicated /tier read).
        CoreProjection p = projection(memberId, programId);
        return new TierState(p.programId(), null, new TierRef(null, p.tierCode()), null,
                p.qualifyingBalance(), null, null);
    }

    private CoreProjection projection(long memberId, long programId) {
        return core.get()
                .uri("/members/{memberId}/programs/{programId}/projection", memberId, programId)
                .retrieve()
                .body(CoreProjection.class);
    }

    public List<ExpiringCohort> listExpiringPoints(long memberId, long programId) {
        return core.get()
                .uri("/members/{memberId}/programs/{programId}/expiring-points", memberId, programId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    static java.net.URI pageUri(UriBuilder uri, String path, String cursor, Integer limit, Object... vars) {
        uri.path(path);
        if (cursor != null) {
            uri.queryParam("cursor", cursor);
        }
        if (limit != null) {
            uri.queryParam("limit", limit);
        }
        return uri.build(vars);
    }

    private record TcsBody(int tcsVersion) {
    }

    // --- core wire shapes (loyalty-core MemberResponse / MemberProjectionResponse) ---------------
    // Field-complete so deserialization never trips on an unexpected property.

    private record OptInRequest(long programId, long customerId, int tcsVersionAccepted) {
    }

    private record CoreMember(Long memberId, Long programId, Long customerId, String status,
                              long redeemableBalance, long qualifyingBalance, String tierCode) {
    }

    private record CoreProjection(Long memberId, Long programId, long redeemableBalance,
                                  long qualifyingBalance, String tierCode, String status, Instant asOf) {
    }
}
