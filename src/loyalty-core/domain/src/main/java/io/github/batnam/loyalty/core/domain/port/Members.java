package io.github.batnam.loyalty.core.domain.port;

import io.github.batnam.loyalty.core.domain.member.Member;

/**
 * Domain port for loading and saving the {@link Member} aggregate as one unit of work
 * (ADR-0001). The contract — invariants the caller must rely on and any adapter must honour:
 *
 * <ul>
 *   <li><b>Same transaction.</b> {@link #loadForUpdate} and {@link #save} for one Member MUST be
 *       called within a single transaction opened by the caller (the application service). The
 *       adapter does not open its own.</li>
 *   <li><b>Lock at load, held through save.</b> {@link #loadForUpdate} acquires the pessimistic row
 *       lock; that lock is held until the surrounding transaction commits, so the read-modify-write
 *       is serialised (the single-writer invariant P5 depends on this). {@link #save} MUST reuse the
 *       row already locked/managed by the load — it MUST NOT re-read or merge a fresh row, which
 *       would move the lock outside the modify window.</li>
 *   <li><b>Atomic drain.</b> {@link #save} persists the aggregate's state change together with its
 *       {@code pendingEntries()}, {@code pendingCohorts()}, and {@code recordedEvents()} (to the
 *       outbox) so the whole posting commits or rolls back as one.</li>
 * </ul>
 *
 * <p>Two adapters satisfy this seam: a JPA-backed one in production and an in-memory fake in tests
 * (so domain-logic tests need no database).
 */
public interface Members {

    /**
     * Load the Member under a pessimistic row lock and rehydrate the aggregate. Throws if the Member
     * does not exist.
     */
    Member loadForUpdate(long memberId);

    /**
     * Persist the aggregate's state change and drain its pending entries, cohorts, and recorded
     * events — within the caller's transaction, against the row locked by {@link #loadForUpdate}.
     */
    void save(Member member);
}
