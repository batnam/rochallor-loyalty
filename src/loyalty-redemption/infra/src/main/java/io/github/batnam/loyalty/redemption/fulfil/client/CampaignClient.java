package io.github.batnam.loyalty.redemption.fulfil.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin client to {@code loyalty-campaign}'s {@code Drawing.recordEntry} (L3 §3.2, T-13 touchpoint), used
 * by {@code SweepstakesAdapter}. Speaks the shipped {@code loyalty-campaign} entry contract:
 * {@code POST /drawings/{id}/entries {memberId, sagaId, idempotencyKey, weight?}} → {@code DrawingEntry}.
 * Idempotent on {@code idempotencyKey} — a Saga retry replays the same entry (200) instead of duplicating.
 */
@Component
public class CampaignClient {

    private final RestClient http;

    public CampaignClient(@Qualifier("campaignRestClient") RestClient campaignRestClient) {
        this.http = campaignRestClient;
    }

    /**
     * Record one sweepstakes entry for a Member; returns the drawing entry id (the adapter's external ref).
     * {@code idempotencyKey} is derived from the Saga key so a retry is a no-op replay; {@code weight} is
     * nullable (only meaningful for {@code WEIGHTED} drawings).
     */
    public String recordEntry(long drawingId, long memberId, long sagaId, String idempotencyKey, Integer weight) {
        EntryResponse resp = http.post()
                .uri("/drawings/{drawingId}/entries", drawingId)
                .body(new EntryRequest(memberId, sagaId, idempotencyKey, weight))
                .retrieve()
                .body(EntryResponse.class);
        if (resp == null || resp.entryId() == null) {
            throw new IllegalStateException("campaign returned no drawing entry id");
        }
        return String.valueOf(resp.entryId());
    }

    public record EntryRequest(long memberId, long sagaId, String idempotencyKey, Integer weight) {
    }

    public record EntryResponse(Long entryId) {
    }
}
