package io.github.batnam.loyalty.mobilebff.api;

import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.RedeemRequest;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.RedemptionResult;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.Reward;
import io.github.batnam.loyalty.mobilebff.api.dto.MobileDtos.RewardPage;
import io.github.batnam.loyalty.mobilebff.client.RedemptionClient;
import io.github.batnam.loyalty.mobilebff.client.RedemptionClient.RedeemOutcome;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rewards & Redemption (loyalty-mobile-bff.yaml). Catalogue reads are filtered by the caller's
 * eligibility upstream; {@code POST /redemptions} forwards the {@code Idempotency-Key} and optional
 * step-up token to loyalty-redemption and mirrors its 200 (sync) / 202 (async) status to the edge.
 */
@RestController
public class RewardController {

    private final RedemptionClient redemption;

    public RewardController(RedemptionClient redemption) {
        this.redemption = redemption;
    }

    @GetMapping("/me/programs/{programId}/rewards")
    public RewardPage listRewards(@RequestParam long customerId, @PathVariable long programId,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit) {
        return redemption.listEligibleRewards(customerId, programId, cursor, limit);
    }

    @GetMapping("/rewards/{rewardId}")
    public Reward getReward(@PathVariable long rewardId) {
        return redemption.getReward(rewardId);
    }

    @PostMapping("/redemptions")
    public ResponseEntity<RedemptionResult> redeem(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken,
            @RequestBody RedeemRequest req) {
        RedeemOutcome outcome = redemption.redeem(req.customerId(), req.accountNumber(), req.programId(),
                req.rewardId(), idempotencyKey, stepUpToken);
        return ResponseEntity.status(outcome.statusCode()).body(outcome.result());
    }

    @GetMapping("/redemptions/{redemptionId}")
    public RedemptionResult getRedemption(@PathVariable long redemptionId) {
        return redemption.getRedemption(redemptionId);
    }
}
