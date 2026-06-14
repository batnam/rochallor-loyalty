package io.github.batnam.loyalty.redemption.saga;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SagaRepository extends JpaRepository<RedemptionSaga, Long> {

    /** Resume Consumer correlates a partner outcome to its saga by the adapter's external ref. */
    Optional<RedemptionSaga> findByExternalRef(String externalRef);

    /** Prior successful redemptions of a Reward by a Member — feeds the per-Member eligibility cap. */
    int countByMemberIdAndRewardIdAndStatus(Long memberId, Long rewardId, SagaStatus status);
}
