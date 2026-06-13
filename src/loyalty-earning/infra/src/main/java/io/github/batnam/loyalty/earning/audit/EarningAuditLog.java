package io.github.batnam.loyalty.earning.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * Tamper-evident admin-write trail (L3 §5; ≥7-year retention). Insert-only (DB trigger +
 * {@code @Immutable}); hash-chained via {@code prevHash}/{@code rowHash} so any retroactive edit
 * breaks the chain. Nightly WORM sealing to S3 Object Lock is out of scope for this scaffold.
 */
@Entity
@Immutable
@Table(name = "earning_audit_log")
public class EarningAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long auditId;

    @Column(name = "actor_keycloak_id")
    private String actorKeycloakId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    @Column(name = "before_json")
    private String beforeJson;

    @Column(name = "after_json")
    private String afterJson;

    @Column(name = "occurred_at", nullable = false, insertable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "prev_hash")
    private String prevHash;

    @Column(name = "row_hash", nullable = false)
    private String rowHash;

    protected EarningAuditLog() {
    }

    static EarningAuditLog of(String actor, String action, String entityType, String entityId,
                              String beforeJson, String afterJson, String prevHash, String rowHash) {
        EarningAuditLog a = new EarningAuditLog();
        a.actorKeycloakId = actor;
        a.action = action;
        a.entityType = entityType;
        a.entityId = entityId;
        a.beforeJson = beforeJson;
        a.afterJson = afterJson;
        a.prevHash = prevHash;
        a.rowHash = rowHash;
        return a;
    }

    public Long getAuditId() { return auditId; }
    public String getRowHash() { return rowHash; }
    public String getPrevHash() { return prevHash; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
}
