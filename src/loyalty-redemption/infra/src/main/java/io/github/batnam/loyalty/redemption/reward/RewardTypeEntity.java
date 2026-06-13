package io.github.batnam.loyalty.redemption.reward;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Platform-seeded Reward Type (GET /reward-types). Read-only at runtime ({@code @Immutable}); rows are
 * inserted by Flyway V2, never by the app. {@code code} matches the {@link io.github.batnam.loyalty.redemption.fulfil.RewardType}
 * enum so the Saga can dispatch by it.
 */
@Entity
@Immutable
@Table(name = "reward_type")
public class RewardTypeEntity {

    @Id
    @Column(name = "reward_type_code")
    private String code;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "fulfillment_adapter_class", nullable = false)
    private String fulfillmentAdapterClass;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameter_schema")
    private String parameterSchema;

    @Column(name = "is_async", nullable = false)
    private boolean async;

    protected RewardTypeEntity() {
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public String getFulfillmentAdapterClass() { return fulfillmentAdapterClass; }
    public String getParameterSchema() { return parameterSchema; }
    public boolean isAsync() { return async; }
}
