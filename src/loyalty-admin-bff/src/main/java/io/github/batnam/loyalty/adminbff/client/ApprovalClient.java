package io.github.batnam.loyalty.adminbff.client;

import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.ApprovalConfirmRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.ApprovalCreateRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.ApprovalRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Anti-Corruption boundary to loyalty-core's approval-request store. Loyalty does <strong>not</strong>
 * implement maker-checker — the bank's BEP Approval Workflow owns routing, Job Roles, 4-eyes, and caps.
 * loyalty-core persists the proposed change as {@code PENDING} and, on {@code confirm/APPROVED}, applies
 * it atomically (writing the {@code Adjusted} ledger entry itself, or invoking the owning service's
 * hardened {@code confirm} seam with the {@code bepApprovalRef}). The admin BFF is a thin forwarder so it
 * stays aggregation-only.
 */
@Component
public class ApprovalClient {

    private final RestClient core;

    public ApprovalClient(@Qualifier("coreRestClient") RestClient coreRestClient) {
        this.core = coreRestClient;
    }

    public ApprovalRequest create(String actor, ApprovalCreateRequest req) {
        return core.post()
                .uri("/approval-requests")
                .header("X-Actor", actor)
                .body(req)
                .retrieve()
                .body(ApprovalRequest.class);
    }

    public List<ApprovalRequest> list(String status, String type) {
        return core.get()
                .uri(uri -> {
                    uri.path("/approval-requests");
                    if (status != null) {
                        uri.queryParam("status", status);
                    }
                    if (type != null) {
                        uri.queryParam("type", type);
                    }
                    return uri.build();
                })
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public ApprovalRequest confirm(long requestId, ApprovalConfirmRequest req) {
        return core.post()
                .uri("/approval-requests/{requestId}/confirm", requestId)
                .body(req)
                .retrieve()
                .body(ApprovalRequest.class);
    }
}
