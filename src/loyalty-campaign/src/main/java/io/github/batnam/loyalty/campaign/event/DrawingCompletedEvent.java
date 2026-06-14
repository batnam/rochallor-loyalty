package io.github.batnam.loyalty.campaign.event;

import io.github.batnam.loyalty.campaign.drawing.Drawing;

import java.time.Instant;
import java.util.List;

/**
 * {@code loyalty.campaign.drawing_completed.v1} envelope (asyncapi/loyalty-campaign.yaml). One summary event
 * per closed Drawing carrying all winner member-ids. {@code eventId} is derived from the drawing id so the
 * event is stably deduplicable downstream. Partitioned by {@code drawingId}.
 */
public record DrawingCompletedEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        Long programId,
        int schemaVersion,
        Long drawingId,
        Long campaignId,
        String selectionStrategy,
        List<Long> winnerMemberIds,
        Instant drawnAt
) {
    public static DrawingCompletedEvent of(Drawing d, List<Long> winnerMemberIds, Instant drawnAt) {
        return new DrawingCompletedEvent(
                "drawing-completed-" + d.getDrawingId(), "DrawingCompleted", drawnAt,
                d.getProgramId(), 1, d.getDrawingId(), d.getCampaignId(),
                d.getSelectionStrategy().name(), winnerMemberIds, drawnAt);
    }
}
