package io.github.batnam.loyalty.earning.caps;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Single writer of {@code cap_counter}. The cap decision is a single conditional UPDATE
 * ({@code WHERE remaining >= :n}) so it is atomic with no SELECT-then-UPDATE race — "0 rows affected"
 * means the cap is exhausted (L3 §3.2). Counters are lazily created on first use.
 */
public interface CapRepository extends JpaRepository<CapCounter, CapCounter.Key> {

    /** Create the counter at its limit on first use; no-op if it already exists. */
    @Modifying
    @Query(value = """
            INSERT INTO cap_counter (program_id, rule_id, member_id, window_key, remaining, expires_at)
            VALUES (:programId, :ruleId, :memberId, :windowKey, :limit, :expiresAt)
            ON CONFLICT (program_id, rule_id, member_id, window_key) DO NOTHING
            """, nativeQuery = true)
    void ensureCounter(@Param("programId") long programId, @Param("ruleId") long ruleId,
                       @Param("memberId") long memberId, @Param("windowKey") String windowKey,
                       @Param("limit") long limit, @Param("expiresAt") Instant expiresAt);

    /** Atomic conditional decrement. Returns rows affected: 1 = applied, 0 = cap exhausted. */
    @Modifying
    @Query(value = """
            UPDATE cap_counter SET remaining = remaining - :n
            WHERE program_id = :programId AND rule_id = :ruleId AND member_id = :memberId
              AND window_key = :windowKey AND remaining >= :n
            """, nativeQuery = true)
    int tryDecrement(@Param("programId") long programId, @Param("ruleId") long ruleId,
                     @Param("memberId") long memberId, @Param("windowKey") String windowKey,
                     @Param("n") long n);

    /** Compensating re-credit when a later window in the same fire is exhausted. */
    @Modifying
    @Query(value = """
            UPDATE cap_counter SET remaining = remaining + :n
            WHERE program_id = :programId AND rule_id = :ruleId AND member_id = :memberId
              AND window_key = :windowKey
            """, nativeQuery = true)
    void credit(@Param("programId") long programId, @Param("ruleId") long ruleId,
                @Param("memberId") long memberId, @Param("windowKey") String windowKey,
                @Param("n") long n);

    /** Nightly purge of expired window counters (LIFE rows have NULL expires_at and are kept). */
    @Modifying
    @Query(value = "DELETE FROM cap_counter WHERE expires_at IS NOT NULL AND expires_at < :now",
            nativeQuery = true)
    int purgeExpired(@Param("now") Instant now);
}
