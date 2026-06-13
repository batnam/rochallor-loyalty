package io.github.batnam.loyalty.redemption.reward;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RewardRepository extends JpaRepository<Reward, Long> {

    List<Reward> findByProgramIdOrderByRewardIdAsc(Long programId);

    List<Reward> findByProgramIdAndStatusOrderByRewardIdAsc(Long programId, RewardStatus status);
}
