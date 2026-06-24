package io.github.batnam.loyalty.earning.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * OPTIONAL per-{@code (programId, earnSourceCode)} Source-Aggregate Cap (V3 {@code earn_source_cap}).
 * Bounds the TOTAL points awardable across ALL earning rules for that source per window; distinct from
 * a per-Rule cap (when both apply, the more restrictive wins). Read-only config; a NULL cap field means
 * "no cap for that window". Absence of a row means the source is uncapped.
 */
@Entity
@Table(name = "earn_source_cap")
@IdClass(EarnSourceCap.Key.class)
public class EarnSourceCap {

    @Id @Column(name = "program_id")       private Long programId;
    @Id @Column(name = "earn_source_code") private String earnSourceCode;

    @Column(name = "daily_cap")    private Long dailyCap;
    @Column(name = "monthly_cap")  private Long monthlyCap;
    @Column(name = "lifetime_cap") private Long lifetimeCap;

    protected EarnSourceCap() {
    }

    public Long getProgramId() { return programId; }
    public String getEarnSourceCode() { return earnSourceCode; }
    public Long getDailyCap() { return dailyCap; }
    public Long getMonthlyCap() { return monthlyCap; }
    public Long getLifetimeCap() { return lifetimeCap; }

    /** Composite primary key for {@link EarnSourceCap} (JPA {@code @IdClass} — field names match). */
    public static class Key implements Serializable {
        private Long programId;
        private String earnSourceCode;

        public Key() {
        }

        public Key(Long programId, String earnSourceCode) {
            this.programId = programId;
            this.earnSourceCode = earnSourceCode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(programId, k.programId) && Objects.equals(earnSourceCode, k.earnSourceCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(programId, earnSourceCode);
        }
    }
}
