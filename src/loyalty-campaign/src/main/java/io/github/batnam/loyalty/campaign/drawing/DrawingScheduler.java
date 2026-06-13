package io.github.batnam.loyalty.campaign.drawing;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Drawing Scheduler (L3 §4 component 5) — an in-pod {@code @Scheduled} job (poll cadence configurable;
 * default every minute), ShedLock-guarded so exactly one pod fires per tick. It finds OPEN Drawings whose
 * {@code draw_at} has passed and runs Winner Selection for each, in its own transaction, so one failing
 * Drawing does not block the rest. The pessimistic lock inside {@link WinnerSelectionService} is the second
 * line of defence against a duplicated fire.
 */
@Component
public class DrawingScheduler {

    private static final Logger log = LoggerFactory.getLogger(DrawingScheduler.class);

    private final DrawingRepository drawings;
    private final WinnerSelectionService selection;

    public DrawingScheduler(DrawingRepository drawings, WinnerSelectionService selection) {
        this.drawings = drawings;
        this.selection = selection;
    }

    @Scheduled(cron = "${campaign.scheduler.poll-cron}")
    @SchedulerLock(name = "campaign-drawing-scheduler")
    public void fireDueDrawings() {
        List<Drawing> due = drawings.findByStatusAndDrawAtLessThanEqualOrderByDrawingIdAsc(
                DrawingStatus.OPEN, Instant.now());
        for (Drawing d : due) {
            try {
                selection.selectWinner(d.getDrawingId());
            } catch (RuntimeException e) {
                log.error("winner selection failed for drawing {} — continuing", d.getDrawingId(), e);
            }
        }
    }
}
