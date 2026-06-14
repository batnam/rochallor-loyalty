package io.github.batnam.loyalty.core.member;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByProgramIdAndCustomerId(Long programId, Long customerId);

    /** All Members a Customer holds across Programs — used to close them on CUSTOMER_CLOSED. */
    List<Member> findByCustomerId(Long customerId);

    /**
     * Pessimistic row lock for the write path: a balance-affecting Ledger insert loads the Member
     * {@code FOR UPDATE} so concurrent earns/commits on the same Member serialise (the single-writer
     * invariant on balance columns is otherwise racy under multi-pod).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.memberId = :memberId")
    Optional<Member> findByIdForUpdate(@Param("memberId") Long memberId);
}
