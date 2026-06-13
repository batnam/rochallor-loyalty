package io.github.batnam.loyalty.campaign.event;

import io.github.batnam.loyalty.campaign.drawing.Drawing;

import java.time.Instant;

/**
 * {@code loyalty.campaign.drawing_void.v1} — a Drawing closed with zero entries (C4 §3.3: "Drawings with
 * zero entries close as VOID and emit a distinct event, so BEP can review and re-run"). Emitted on a
 * distinct configurable topic rather than overloading DrawingCompleted with an empty winner set;
 * catalogued as the {@code DrawingVoid} message in {@code asyncapi/loyalty-campaign.yaml}.
 */
public record DrawingVoidEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        Long programId,
        int schemaVersion,
        Long drawingId,
        Long campaignId,
        Instant voidedAt
) {
    public static DrawingVoidEvent of(Drawing d, Instant voidedAt) {
        return new DrawingVoidEvent(
                "drawing-void-" + d.getDrawingId(), "DrawingVoid", voidedAt,
                d.getProgramId(), 1, d.getDrawingId(), d.getCampaignId(), voidedAt);
    }
}
