package io.github.batnam.loyalty.campaign.drawing;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DrawingRepository extends JpaRepository<Drawing, Long> {

    List<Drawing> findByCampaignIdOrderByDrawingIdAsc(long campaignId);

    /** Drawings the scheduler should fire: still OPEN and past their draw time. */
    List<Drawing> findByStatusAndDrawAtLessThanEqualOrderByDrawingIdAsc(DrawingStatus status, Instant now);

    /**
     * Pessimistic-write lock on a single Drawing for Winner Selection (L3 §3.3) — only one pod may select a
     * winner per drawing. Belt to the ShedLock + {@code status=OPEN} predicate braces against duplicated
     * fires from clock skew.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Drawing d where d.drawingId = :id")
    Optional<Drawing> findByIdForUpdate(@Param("id") long id);
}
