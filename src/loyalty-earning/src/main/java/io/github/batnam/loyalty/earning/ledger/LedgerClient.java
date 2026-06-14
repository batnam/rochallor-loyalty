package io.github.batnam.loyalty.earning.ledger;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The Ledger Client (L3 §4 component 8) — a thin REST wrapper over loyalty-core's {@code POST
 * /ledger/earn}, the Anti-Corruption boundary. Its sole job is to send the earn request with the
 * right {@code Idempotency-Key} header; all Ledger semantics belong to core. Returns the created (or
 * replayed) ledger entry id.
 */
@Component
public class LedgerClient {

    private final RestClient core;

    public LedgerClient(RestClient coreRestClient) {
        this.core = coreRestClient;
    }

    /** @param idempotencyKey core dedups on {@code (sourceRef, entryType)}; the header is the second line of defence. */
    public Long appendEarned(LedgerEarnRequest req, String idempotencyKey) {
        LedgerEntryResponse resp = core.post()
                .uri("/ledger/earn")
                .header("Idempotency-Key", idempotencyKey)
                .body(req)
                .retrieve()
                .body(LedgerEntryResponse.class);
        if (resp == null) {
            throw new IllegalStateException("loyalty-core /ledger/earn returned no body for " + req.sourceRef());
        }
        return resp.entryId();
    }
}
