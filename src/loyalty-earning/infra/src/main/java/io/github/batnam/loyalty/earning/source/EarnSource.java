package io.github.batnam.loyalty.earning.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Catalogue row for an accepted Earn Source code (L3 §5 {@code earn_source}). Tied to an upstream
 * producer topic via the bridge's translation; read-only config in earning (seeded in V2).
 */
@Entity
@Table(name = "earn_source")
public class EarnSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "earn_source_id")
    private Long earnSourceId;

    @Column(name = "earn_source_code", nullable = false, updatable = false)
    private String earnSourceCode;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "active_by_default", nullable = false)
    private boolean activeByDefault;

    protected EarnSource() {
    }

    public Long getEarnSourceId() { return earnSourceId; }
    public String getEarnSourceCode() { return earnSourceCode; }
    public String getDisplayName() { return displayName; }
    public boolean isActiveByDefault() { return activeByDefault; }
}
