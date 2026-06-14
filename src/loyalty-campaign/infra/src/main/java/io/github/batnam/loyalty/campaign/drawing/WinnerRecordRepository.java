package io.github.batnam.loyalty.campaign.drawing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WinnerRecordRepository extends JpaRepository<WinnerRecord, Long> {

    /** The K winners of a Drawing, in slot order (audit view + GET /drawings/{id}/winners). */
    List<WinnerRecord> findByDrawingIdOrderByWinnerIndexAsc(long drawingId);
}
