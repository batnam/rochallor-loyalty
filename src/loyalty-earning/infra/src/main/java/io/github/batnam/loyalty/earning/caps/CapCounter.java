package io.github.batnam.loyalty.earning.caps;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Per-{@code (programId, ruleId, memberId, window)} remaining-points counter (L3 §5 {@code cap_counter}).
 * Initialised to the cap limit on first use and decremented by a single conditional UPDATE in
 * {@link CapRepository} (no SELECT-then-UPDATE race). This entity is the read/insert mapping; the
 * decrement itself is a native modifying query.
 */
@Entity
@Table(name = "cap_counter")
@IdClass(CapCounter.Key.class)
public class CapCounter {

    @Id @Column(name = "program_id") private Long programId;
    @Id @Column(name = "rule_id")    private Long ruleId;
    @Id @Column(name = "member_id")  private Long memberId;
    @Id @Column(name = "window_key") private String windowKey;

    @Column(name = "remaining", nullable = false) private long remaining;
    @Column(name = "expires_at") private Instant expiresAt;

    protected CapCounter() {
    }

    public Long getProgramId() { return programId; }
    public Long getRuleId() { return ruleId; }
    public Long getMemberId() { return memberId; }
    public String getWindowKey() { return windowKey; }
    public long getRemaining() { return remaining; }
    public Instant getExpiresAt() { return expiresAt; }

    /** Composite primary key for {@link CapCounter} (JPA {@code @IdClass} — field names match). */
    public static class Key implements Serializable {
        private Long programId;
        private Long ruleId;
        private Long memberId;
        private String windowKey;

        public Key() {
        }

        public Key(Long programId, Long ruleId, Long memberId, String windowKey) {
            this.programId = programId;
            this.ruleId = ruleId;
            this.memberId = memberId;
            this.windowKey = windowKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(programId, k.programId) && Objects.equals(ruleId, k.ruleId)
                    && Objects.equals(memberId, k.memberId) && Objects.equals(windowKey, k.windowKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(programId, ruleId, memberId, windowKey);
        }
    }
}
