package io.github.batnam.loyalty.campaign.drawing;

import io.github.batnam.loyalty.campaign.config.CampaignProperties;
import io.github.batnam.loyalty.campaign.event.DrawingCompletedEvent;
import io.github.batnam.loyalty.campaign.event.DrawingVoidEvent;
import io.github.batnam.loyalty.campaign.event.WinnerSelectedEvent;
import io.github.batnam.loyalty.campaign.outbox.OutboxRelay;
import io.github.batnam.loyalty.campaign.select.WinnerSelection;
import io.github.batnam.loyalty.campaign.select.WinnerSelection.Winner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Winner Selection (L3 §3.3 / §4 component 6) — fired by the {@link DrawingScheduler} when a Drawing reaches
 * its {@code draw_at}. Runs in one transaction per Drawing:
 * <ol>
 *   <li>pessimistic-lock the Drawing ({@code SELECT … FOR UPDATE}) and re-check it is still OPEN — a
 *       terminal Drawing is a no-op (duplicated fire);</li>
 *   <li>read the immutable entry order; N=0 → close VOID + emit DrawingVoid;</li>
 *   <li>else pick K = min(winnersCount, N) winners without replacement via the strategy, write K immutable
 *       {@code winner_record} rows, close the Drawing, and emit one DrawingCompleted + K WinnerSelected.</li>
 * </ol>
 * Prize fulfilment is delegated to loyalty-redemption (the only service that knows how to fulfil a Reward).
 */
@Service
public class WinnerSelectionService {

    private static final Logger log = LoggerFactory.getLogger(WinnerSelectionService.class);
    private static final String AGGREGATE = "Drawing";

    private final DrawingRepository drawings;
    private final DrawingEntryRepository entries;
    private final WinnerRecordRepository winners;
    private final OutboxRelay outbox;
    private final CampaignProperties props;

    public WinnerSelectionService(DrawingRepository drawings, DrawingEntryRepository entries,
                                  WinnerRecordRepository winners, OutboxRelay outbox, CampaignProperties props) {
        this.drawings = drawings;
        this.entries = entries;
        this.winners = winners;
        this.outbox = outbox;
        this.props = props;
    }

    @Transactional
    public void selectWinner(long drawingId) {
        Drawing drawing = drawings.findByIdForUpdate(drawingId).orElse(null);
        if (drawing == null) {
            log.warn("winner selection for unknown drawing {} — ignoring", drawingId);
            return;
        }
        if (drawing.getStatus().isTerminal()) {
            log.debug("drawing {} already {} — winner selection no-op", drawingId, drawing.getStatus());
            return;
        }

        Instant drawnAt = Instant.now();
        List<DrawingEntry> ordered = entries.findByDrawingIdOrderByEntryIdAsc(drawingId);

        if (ordered.isEmpty()) {
            drawing.markVoid();
            drawings.save(drawing);
            outbox.enqueue(AGGREGATE, "DrawingVoid", props.topics().drawingVoid(),
                    String.valueOf(drawingId), DrawingVoidEvent.of(drawing, drawnAt));
            log.info("drawing {} drawn with zero entries — VOID", drawingId);
            return;
        }

        List<WinnerSelection.Entry> pool = toSelectionEntries(ordered);
        List<Winner> picked = WinnerSelection.select(drawingId, drawing.getDrawAt(),
                props.selection().hmacSecret(), pool, drawing.getSelectionStrategy(), drawing.getWinnersCount());

        for (Winner w : picked) {
            winners.save(WinnerRecord.of(drawingId, w.memberId(), w.winnerIndex(), w.seedHex()));
        }
        drawing.close();
        drawings.save(drawing);

        emitOutcomes(drawing, picked, drawnAt);
        log.info("drawing {} closed with {} winner(s) via {}", drawingId, picked.size(),
                drawing.getSelectionStrategy());
    }

    private void emitOutcomes(Drawing drawing, List<Winner> picked, Instant drawnAt) {
        List<Long> winnerMemberIds = picked.stream().map(Winner::memberId).toList();
        outbox.enqueue(AGGREGATE, "DrawingCompleted", props.topics().drawingCompleted(),
                String.valueOf(drawing.getDrawingId()),
                DrawingCompletedEvent.of(drawing, winnerMemberIds, drawnAt));
        for (Winner w : picked) {
            WinnerRecord row = WinnerRecord.of(drawing.getDrawingId(), w.memberId(), w.winnerIndex(), w.seedHex());
            outbox.enqueue(AGGREGATE, "WinnerSelected", props.topics().winnerSelected(),
                    String.valueOf(w.memberId()), WinnerSelectedEvent.of(drawing, row, drawnAt));
        }
    }

    /** Map persisted entries to the algorithm's value objects, index = position in entry-id order. */
    private static List<WinnerSelection.Entry> toSelectionEntries(List<DrawingEntry> ordered) {
        return java.util.stream.IntStream.range(0, ordered.size())
                .mapToObj(i -> new WinnerSelection.Entry(ordered.get(i).getMemberId(), i, ordered.get(i).getWeight()))
                .toList();
    }
}
