package io.github.batnam.loyalty.campaign.event;

import io.github.batnam.loyalty.campaign.drawing.Drawing;
import io.github.batnam.loyalty.campaign.drawing.WinnerRecord;

import java.time.Instant;

/**
 * {@code loyalty.campaign.winner_selected.v1} envelope (asyncapi/loyalty-campaign.yaml). One event per
 * selected winner, carrying the auditable seeded-RNG proof ({@code seedHex} + {@code winnerIndex}).
 * Partitioned by {@code memberId}. {@code prizeRewardId} is null in v1 — prize fulfilment is a direct call
 * to loyalty-redemption (asyncapi note), not driven by this event. {@code eventId} is derived from the
 * drawing id + winner index so it is stably deduplicable.
 */
public record WinnerSelectedEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        Long programId,
        int schemaVersion,
        Long drawingId,
        Long campaignId,
        Long memberId,
        Long prizeRewardId,
        String selectionStrategy,
        int winnerIndex,
        String seedHex,
        Instant drawnAt
) {
    public static WinnerSelectedEvent of(Drawing d, WinnerRecord w, Instant drawnAt) {
        return new WinnerSelectedEvent(
                "winner-selected-" + d.getDrawingId() + "-" + w.getWinnerIndex(), "WinnerSelected", drawnAt,
                d.getProgramId(), 1, d.getDrawingId(), d.getCampaignId(), w.getMemberId(), null,
                d.getSelectionStrategy().name(), w.getWinnerIndex(), w.getSeedHex(), drawnAt);
    }
}
