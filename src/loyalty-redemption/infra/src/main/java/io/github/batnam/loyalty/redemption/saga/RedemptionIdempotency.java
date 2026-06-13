package io.github.batnam.loyalty.redemption.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps a submit {@code Idempotency-Key} to the saga it created ({@code redemption_idempotency}), so a
 * replayed {@code POST /redemptions} returns the original outcome instead of running the Saga twice
 * (loyalty-redemption.yaml idempotency contract).
 */
@Entity
@Table(name = "redemption_idempotency")
public class RedemptionIdempotency {

    @Id
    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "saga_id", nullable = false, updatable = false)
    private Long sagaId;

    protected RedemptionIdempotency() {
    }

    public static RedemptionIdempotency of(String idempotencyKey, Long sagaId) {
        RedemptionIdempotency k = new RedemptionIdempotency();
        k.idempotencyKey = idempotencyKey;
        k.sagaId = sagaId;
        return k;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public Long getSagaId() { return sagaId; }
}
