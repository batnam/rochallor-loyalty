package io.github.batnam.loyalty.core.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Master Member record per {@code (programId, customerId)} (CONTEXT.md "Member"). PII-free.
 *
 * <p>Single-writer-per-column (P5): {@code status} is written only by {@code MembershipAggregate};
 * {@code redeemableBalance}/{@code qualifyingBalance} only by {@code BalanceProjection};
 * {@code currentTierCode} only by {@code TierAuthority} (the windowed Tier projection path). They share this one managed entity inside
 * a single transaction. The setters below are package-private so only those components mutate them.
 */
@Entity
@Table(name = "member")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "program_id", nullable = false, updatable = false)
    private Long programId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MemberStatus status = MemberStatus.ACTIVE;

    @Column(name = "redeemable_balance", nullable = false)
    private long redeemableBalance;

    @Column(name = "qualifying_balance", nullable = false)
    private long qualifyingBalance;

    @Column(name = "current_tier_code")
    private String currentTierCode;

    @Column(name = "tcs_version_accepted")
    private Integer tcsVersionAccepted;

    @Column(name = "enrolled_at", nullable = false, updatable = false, insertable = false)
    private Instant enrolledAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.EPOCH;

    protected Member() {
    }

    public static Member enroll(Long programId, Long customerId, Integer tcsVersionAccepted) {
        Member m = new Member();
        m.programId = programId;
        m.customerId = customerId;
        m.tcsVersionAccepted = tcsVersionAccepted;
        m.status = MemberStatus.ACTIVE;
        m.updatedAt = Instant.now();
        return m;
    }

    public Long getMemberId() { return memberId; }
    public Long getProgramId() { return programId; }
    public Long getCustomerId() { return customerId; }
    public MemberStatus getStatus() { return status; }
    public long getRedeemableBalance() { return redeemableBalance; }
    public long getQualifyingBalance() { return qualifyingBalance; }
    public String getCurrentTierCode() { return currentTierCode; }
    public Integer getTcsVersionAccepted() { return tcsVersionAccepted; }
    public Instant getEnrolledAt() { return enrolledAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // --- column-scoped mutators (single-writer enforcement by visibility) ---

    /** Written only by {@code MembershipAggregate}. */
    public void setStatus(MemberStatus status) { this.status = status; touch(); }

    /** Written only by {@code BalanceProjection}. */
    public void applyBalanceDelta(long redeemableDelta, long qualifyingDelta) {
        this.redeemableBalance += redeemableDelta;
        this.qualifyingBalance += qualifyingDelta;
        touch();
    }

    /** Written only by {@code TierAuthority} (the windowed Tier projection path). */
    public void setCurrentTierCode(String currentTierCode) { this.currentTierCode = currentTierCode; touch(); }

    public void setTcsVersionAccepted(Integer v) { this.tcsVersionAccepted = v; touch(); }

    private void touch() { this.updatedAt = Instant.now(); }
}
