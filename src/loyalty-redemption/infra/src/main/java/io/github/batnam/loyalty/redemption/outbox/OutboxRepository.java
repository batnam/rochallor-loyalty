package io.github.batnam.loyalty.redemption.outbox;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEntry, Long> {

    /** Oldest PENDING rows first, capped to the relay batch size. */
    List<OutboxEntry> findByStatusOrderByCreatedAtAsc(OutboxEntry.Status status, Limit limit);
}
