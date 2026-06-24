package io.github.batnam.loyalty.core.program;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;

/**
 * One rung of a Program's Tier Ladder (CONTEXT.md "Tier", "Tier Ladder", "Tier Expiry Override").
 * Read-only config in core (seeded; authoritative owner is loyalty-earning).
 */
@Entity
@Immutable
@Table(name = "tier")
public class Tier {

    @Id
    @Column(name = "tier_id")
    private Long tierId;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Column(name = "tier_code", nullable = false)
    private String tierCode;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "qualifying_threshold", nullable = false)
    private long qualifyingThreshold;

    @Column(name = "expiry_months_override")
    private Integer expiryMonthsOverride;

    @Column(name = "earn_multiplier", nullable = false)
    private BigDecimal earnMultiplier;

    protected Tier() {
    }

    public Long getTierId() { return tierId; }
    public Long getProgramId() { return programId; }
    public String getTierCode() { return tierCode; }
    public int getOrdinal() { return ordinal; }
    public long getQualifyingThreshold() { return qualifyingThreshold; }
    public Integer getExpiryMonthsOverride() { return expiryMonthsOverride; }
    public BigDecimal getEarnMultiplier() { return earnMultiplier; }
}
