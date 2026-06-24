package io.github.batnam.loyalty.core.program;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * Program config (CONTEXT.md "Program", "Qualifying Metric", "Expiry"). Read-only in core.
 *
 * <p>SCAFFOLDING: authoritative owner is {@code loyalty-earning}; this is a seeded local copy (V2)
 * so the Tier Projection and Expiry Job can run standalone. Sync mechanism is deferred.
 */
@Entity
@Immutable
@Table(name = "program")
public class Program {

    @Id
    @Column(name = "program_id")
    private Long programId;

    @Column(name = "program_code", nullable = false)
    private String programCode;

    @Column(name = "qualifying_metric", nullable = false)
    private String qualifyingMetric;

    @Column(name = "expiry_months", nullable = false)
    private int expiryMonths;

    @Column(name = "current_tcs_version", nullable = false)
    private int currentTcsVersion;

    @Column(name = "tcs_version_effective_at", nullable = false)
    private Instant tcsVersionEffectiveAt;

    protected Program() {
    }

    public Long getProgramId() { return programId; }
    public String getProgramCode() { return programCode; }
    public String getQualifyingMetric() { return qualifyingMetric; }
    public int getExpiryMonths() { return expiryMonths; }
    public int getCurrentTcsVersion() { return currentTcsVersion; }
    public Instant getTcsVersionEffectiveAt() { return tcsVersionEffectiveAt; }
}
