package io.github.batnam.loyalty.adminbff.client;

import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.LedgerPage;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.MemberDetail;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.MemberSummary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Anti-Corruption boundary to loyalty-core for the member admin read surface: lookup by customerId,
 * member detail with enrolled programs, and the full Point Ledger audit view.
 */
@Component
public class MemberClient {

    private final RestClient core;

    public MemberClient(@Qualifier("coreRestClient") RestClient coreRestClient) {
        this.core = coreRestClient;
    }

    public List<MemberSummary> findMembers(long customerId) {
        return core.get()
                .uri(uri -> uri.path("/members").queryParam("customerId", customerId).build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public MemberDetail getMember(long memberId) {
        return core.get()
                .uri("/members/{memberId}", memberId)
                .retrieve()
                .body(MemberDetail.class);
    }

    public LedgerPage getMemberLedger(long memberId, long programId, String cursor, Integer limit) {
        return core.get()
                .uri(uri -> {
                    uri.path("/members/{memberId}/programs/{programId}/ledger");
                    if (cursor != null) {
                        uri.queryParam("cursor", cursor);
                    }
                    if (limit != null) {
                        uri.queryParam("limit", limit);
                    }
                    return uri.build(memberId, programId);
                })
                .retrieve()
                .body(LedgerPage.class);
    }
}
