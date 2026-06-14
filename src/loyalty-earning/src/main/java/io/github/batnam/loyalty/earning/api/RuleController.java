package io.github.batnam.loyalty.earning.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.earning.api.dto.EarningDtos.DryRunReport;
import io.github.batnam.loyalty.earning.api.dto.EarningDtos.DryRunRequest;
import io.github.batnam.loyalty.earning.api.dto.EarningDtos.EarningRuleResponse;
import io.github.batnam.loyalty.earning.api.dto.EarningDtos.RuleCreateRequest;
import io.github.batnam.loyalty.earning.api.dto.EarningDtos.RuleStatusRequest;
import io.github.batnam.loyalty.earning.error.EarningException;
import io.github.batnam.loyalty.earning.replay.DryRunService;
import io.github.batnam.loyalty.earning.source.EarnSourceRegistry;
import io.github.batnam.loyalty.earning.source.EarningRule;
import io.github.batnam.loyalty.earning.source.RuleStatus;
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
 * Earning Rule authoring / dry-run / status API (loyalty-earning.yaml, tag Earning Rules).
 * Internal-only; the caller ({@code loyalty-admin-bff}) has already validated the employee JWT + BEP
 * role, so the operator identity arrives as the {@code X-Actor-Id} header for the audit trail.
 */
@RestController
public class RuleController {

    private final EarnSourceRegistry registry;
    private final DryRunService dryRun;
    private final ObjectMapper mapper;

    public RuleController(EarnSourceRegistry registry, DryRunService dryRun, ObjectMapper mapper) {
        this.registry = registry;
        this.dryRun = dryRun;
        this.mapper = mapper;
    }

    @GetMapping("/programs/{programId}/rules")
    public List<EarningRuleResponse> list(@PathVariable long programId,
                                          @RequestParam(required = false) RuleStatus status,
                                          @RequestParam(required = false) Long earnSourceId) {
        return registry.listRules(programId, status, earnSourceId).stream()
                .map(r -> EarningRuleResponse.from(r, mapper)).toList();
    }

    @PostMapping("/programs/{programId}/rules")
    public ResponseEntity<EarningRuleResponse> create(@PathVariable long programId,
                                                      @RequestHeader(value = "X-Actor-Id", defaultValue = "unknown") String actor,
                                                      @RequestBody RuleCreateRequest req) {
        // Web layer is Jackson 3; convert the plain dslJson tree to a Jackson-2 node for the
        // (networknt) validator + storage via the app's Jackson-2 ObjectMapper.
        EarningRule rule = registry.createRule(actor, programId, req.earnSourceId(),
                mapper.valueToTree(req.dslJson()), req.effectiveFrom(), req.effectiveTo(), req.campaignId());
        return ResponseEntity.status(HttpStatus.CREATED).body(EarningRuleResponse.from(rule, mapper));
    }

    @GetMapping("/rules/{ruleId}")
    public EarningRuleResponse get(@PathVariable long ruleId) {
        return EarningRuleResponse.from(registry.getRule(ruleId), mapper);
    }

    @PatchMapping("/rules/{ruleId}")
    public EarningRuleResponse updateStatus(@PathVariable long ruleId,
                                            @RequestHeader(value = "X-Actor-Id", defaultValue = "unknown") String actor,
                                            @RequestBody RuleStatusRequest req) {
        RuleStatus target;
        try {
            target = RuleStatus.valueOf(req.status());
        } catch (IllegalArgumentException e) {
            throw EarningException.conflict("ILLEGAL_TRANSITION", "unknown status: " + req.status());
        }
        EarningRule rule = registry.transitionStatus(actor, ruleId, target, req.bepApprovalRef());
        return EarningRuleResponse.from(rule, mapper);
    }

    @PostMapping("/programs/{programId}/rules/{ruleId}/dry-run")
    public DryRunReport dryRun(@PathVariable long programId, @PathVariable long ruleId,
                               @RequestBody DryRunRequest req) {
        return dryRun.dryRun(ruleId, req.from(), req.to());
    }
}
