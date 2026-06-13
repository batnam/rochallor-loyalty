package io.github.batnam.loyalty.redemption.reward;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Inventory ops use a single conditional UPDATE — no SELECT-then-UPDATE race. {@link #tryReserve}
 * decrements iff stock remains (0 rows affected => exhausted); {@link #restore} compensates on release.
 */
public interface RewardInventoryRepository extends JpaRepository<RewardInventory, Long> {

    @Modifying
    @Query(value = """
            UPDATE reward_inventory SET remaining = remaining - 1, updated_at = now()
            WHERE reward_id = :rewardId AND remaining >= 1
            """, nativeQuery = true)
    int tryReserve(@Param("rewardId") long rewardId);

    @Modifying
    @Query(value = """
            UPDATE reward_inventory SET remaining = LEAST(remaining + 1, total), updated_at = now()
            WHERE reward_id = :rewardId
            """, nativeQuery = true)
    int restore(@Param("rewardId") long rewardId);
}
