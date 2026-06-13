package io.github.batnam.loyalty.redemption.reward;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Optional per-Reward inventory cap ({@code reward_inventory}, PK = reward_id). Decremented atomically
 * on reserve via a conditional UPDATE in {@link RewardInventoryRepository}; restored on release. No row
 * for a Reward means unlimited inventory.
 */
@Entity
@Table(name = "reward_inventory")
public class RewardInventory {

    @Id
    @Column(name = "reward_id")
    private Long rewardId;

    @Column(name = "total", nullable = false)
    private long total;

    @Column(name = "remaining", nullable = false)
    private long remaining;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected RewardInventory() {
    }

    public static RewardInventory of(Long rewardId, long total) {
        RewardInventory i = new RewardInventory();
        i.rewardId = rewardId;
        i.total = total;
        i.remaining = total;
        return i;
    }

    public Long getRewardId() { return rewardId; }
    public long getTotal() { return total; }
    public long getRemaining() { return remaining; }
}
