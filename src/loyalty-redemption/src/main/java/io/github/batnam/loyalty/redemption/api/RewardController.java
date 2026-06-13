package io.github.batnam.loyalty.redemption.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.redemption.api.dto.RedemptionDtos.RewardCreateRequest;
import io.github.batnam.loyalty.redemption.api.dto.RedemptionDtos.RewardResponse;
import io.github.batnam.loyalty.redemption.api.dto.RedemptionDtos.RewardTypeResponse;
import io.github.batnam.loyalty.redemption.api.dto.RedemptionDtos.RewardUpdateRequest;
import io.github.batnam.loyalty.redemption.elig.EligibilityEngine;
import io.github.batnam.loyalty.redemption.elig.EligibilityEngine.MemberSnapshot;
import io.github.batnam.loyalty.redemption.elig.EligibilityRules;
import io.github.batnam.loyalty.redemption.error.RedemptionException;
import io.github.batnam.loyalty.redemption.ledger.LedgerClient;
import io.github.batnam.loyalty.redemption.ledger.LedgerDtos.MemberProjectionResponse;
import io.github.batnam.loyalty.redemption.reward.Reward;
import io.github.batnam.loyalty.redemption.reward.RewardCatalogue;
import io.github.batnam.loyalty.redemption.reward.RewardStatus;
import io.github.batnam.loyalty.redemption.saga.SagaRepository;
import io.github.batnam.loyalty.redemption.saga.SagaStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reward Types + Rewards API (loyalty-redemption.yaml, tags Reward Types / Rewards). Read paths serve
 * both BFFs; the admin writes (create DRAFT, PATCH status/pointCost/inventory) are approval-gated in the
 * {@link RewardCatalogue} and hash-chain audited. The {@code /eligible} read filters the active catalogue
 * through the Eligibility Engine for a specific member.
 */
@RestController
public class RewardController {

    private final RewardCatalogue catalogue;
    private final EligibilityEngine eligibility;
    private final LedgerClient ledger;
    private final SagaRepository sagas;
    private final ObjectMapper mapper;

    public RewardController(RewardCatalogue catalogue, EligibilityEngine eligibility, LedgerClient ledger,
                            SagaRepository sagas, ObjectMapper appObjectMapper) {
        this.catalogue = catalogue;
        this.eligibility = eligibility;
        this.ledger = ledger;
        this.sagas = sagas;
        this.mapper = appObjectMapper;
    }

    @GetMapping("/reward-types")
    public List<RewardTypeResponse> listRewardTypes() {
        return catalogue.listRewardTypes().stream().map(t -> RewardTypeResponse.from(t, mapper)).toList();
    }

    @GetMapping("/programs/{programId}/rewards")
    public List<RewardResponse> listRewards(@PathVariable long programId,
                                            @RequestParam(required = false) RewardStatus status) {
        return catalogue.listByProgram(programId, status).stream().map(this::toResponse).toList();
    }

    @PostMapping("/programs/{programId}/rewards")
    public ResponseEntity<RewardResponse> createReward(@PathVariable long programId,
                                                       @RequestHeader(value = "X-Actor", defaultValue = "admin-bff") String actor,
                                                       @RequestBody RewardCreateRequest req) {
        EligibilityRules rules = req.eligibility() == null ? null
                : mapper.convertValue(req.eligibility(), EligibilityRules.class);
        long cost = req.pointCost() == null ? 0 : req.pointCost();
        Reward created = catalogue.createReward(actor, programId, req.rewardTypeCode(), req.name(),
                cost, req.fulfillmentParams(), rules, req.inventoryTotal());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @GetMapping("/programs/{programId}/rewards/eligible")
    public List<RewardResponse> listEligible(@PathVariable long programId, @RequestParam long memberId) {
        MemberProjectionResponse projection = ledger.projection(memberId, programId);
        MemberSnapshot member = new MemberSnapshot(null, null, null, projection.redeemableBalance(), null);
        return catalogue.listActive(programId).stream()
                .filter(r -> eligibility.isEligible(true, catalogue.eligibilityRulesFor(r.getRewardId()),
                        member, r.getPointCost(),
                        sagas.countByMemberIdAndRewardIdAndStatus(memberId, r.getRewardId(), SagaStatus.COMMITTED)))
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/rewards/{rewardId}")
    public RewardResponse getReward(@PathVariable long rewardId) {
        return toResponse(catalogue.get(rewardId));
    }

    @PatchMapping("/rewards/{rewardId}")
    public RewardResponse updateReward(@PathVariable long rewardId,
                                       @RequestHeader(value = "X-Actor", defaultValue = "admin-bff") String actor,
                                       @RequestBody RewardUpdateRequest req) {
        RewardStatus status = req.status() == null ? null : parseStatus(req.status());
        Reward updated = catalogue.updateReward(actor, rewardId, status, req.pointCost(),
                req.inventoryTotal(), req.bepApprovalRef());
        return toResponse(updated);
    }

    private RewardResponse toResponse(Reward r) {
        return RewardResponse.from(r, catalogue.inventoryFor(r.getRewardId()).orElse(null), mapper);
    }

    private static RewardStatus parseStatus(String raw) {
        try {
            return RewardStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw RedemptionException.badRequest("BAD_STATUS", "unknown reward status " + raw);
        }
    }
}
