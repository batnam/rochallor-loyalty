package io.github.batnam.loyalty.campaign.drawing;

import io.github.batnam.loyalty.campaign.error.CampaignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entry Service (L3 §4 component 4) — the T-13 surface. Records one Member entry into an OPEN Drawing via a
 * single window-gated conditional INSERT (no SELECT-then-INSERT race), idempotent on {@code idempotencyKey}.
 *
 * <p>0 rows affected is disambiguated by looking the key up: an existing entry means an idempotent replay
 * (return it, HTTP 200); none means the Drawing is closed or the window has passed (HTTP 409
 * {@code DRAWING_CLOSED}). A duplicate is a successful no-op — we never fail the calling Saga on a replay.
 */
@Service
public class EntryService {

    private static final Logger log = LoggerFactory.getLogger(EntryService.class);

    private final DrawingRepository drawings;
    private final DrawingEntryRepository entries;

    public EntryService(DrawingRepository drawings, DrawingEntryRepository entries) {
        this.drawings = drawings;
        this.entries = entries;
    }

    /** A recorded entry plus whether this call created it (201) or replayed an existing one (200). */
    public record RecordResult(DrawingEntry entry, boolean created) {
    }

    @Transactional
    public RecordResult recordEntry(long drawingId, long memberId, Long sagaId, String idempotencyKey,
                                    Integer weight) {
        if (!drawings.existsById(drawingId)) {
            throw CampaignException.notFound("DRAWING_NOT_FOUND", "drawing " + drawingId);
        }
        int rows = entries.insertIfWindowOpen(drawingId, memberId, sagaId, idempotencyKey,
                weight == null ? 1 : Math.max(1, weight));
        if (rows == 1) {
            DrawingEntry created = entries.findByIdempotencyKey(idempotencyKey).orElseThrow();
            return new RecordResult(created, true);
        }
        // 0 rows: either a duplicate key (replay) or the window/status predicate failed.
        return entries.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    log.debug("idempotent entry replay for drawing {} key {}", drawingId, idempotencyKey);
                    return new RecordResult(existing, false);
                })
                .orElseThrow(() -> CampaignException.conflict("DRAWING_CLOSED",
                        "drawing " + drawingId + " is closed or outside its entry window"));
    }
}
