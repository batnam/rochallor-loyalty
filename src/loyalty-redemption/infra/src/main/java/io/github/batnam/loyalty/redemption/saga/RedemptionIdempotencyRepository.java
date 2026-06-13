package io.github.batnam.loyalty.redemption.saga;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RedemptionIdempotencyRepository extends JpaRepository<RedemptionIdempotency, String> {
}
