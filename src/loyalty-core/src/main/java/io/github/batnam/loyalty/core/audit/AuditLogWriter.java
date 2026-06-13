package io.github.batnam.loyalty.core.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Single writer of {@code core_audit_log}. Records every admin-originated write as a hash-chained,
 * insert-only row: {@code rowHash = SHA-256(prevHash ‖ actor ‖ action ‖ entityType ‖ entityId ‖
 * before ‖ after)}. Any retroactive tampering breaks the chain (CONTEXT.md "Service Audit Log").
 *
 * <p>Runs {@code Propagation.MANDATORY} — it must enlist in the caller's business transaction so the
 * audit row and the change it describes commit-or-roll-back together.
 */
@Service
public class AuditLogWriter {

    private final AuditRepository audit;

    public AuditLogWriter(AuditRepository audit) {
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, String action, String entityType, String entityId,
                       String beforeJson, String afterJson) {
        String prevHash = audit.findTopByOrderByAuditIdDesc()
                .map(CoreAuditLog::getRowHash)
                .orElse("");
        String rowHash = hash(prevHash, actor, action, entityType, entityId, beforeJson, afterJson);
        audit.save(CoreAuditLog.of(actor, action, entityType, entityId, beforeJson, afterJson, prevHash, rowHash));
    }

    private static String hash(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                md.update((p == null ? "" : p).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0x1f);   // unit separator so field boundaries are unambiguous
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
