package io.github.batnam.loyalty.adminbff.api;

import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.DryRunResult;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.EarnSource;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.EarningRule;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.RuleCreateRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.RuleDryRunRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.RuleStatusRequest;
import io.github.batnam.loyalty.adminbff.client.EarningClient;
import io.github.batnam.loyalty.adminbff.security.EmployeeIdentity;
import io.github.batnam.loyalty.adminbff.security.Roles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Earning Rules (loyalty-admin-bff.yaml). Earn Source registry + Earning Rule authoring, dry-run, and
 * activation, aggregated from loyalty-earning. Authoring requires the campaign-manager role; activation
 * (→ ACTIVE) is approval-gated upstream.
 */
@RestController
public class EarningRuleController {

    private final EarningClient earning;

    public EarningRuleController(EarningClient earning) {
        this.earning = earning;
    }

    @GetMapping("/programs/{programId}/earn-sources")
    public List<EarnSource> listEarnSources(EmployeeIdentity caller, @PathVariable long programId) {
        caller.requireAnyRole(Roles.CAMPAIGN_MANAGER, Roles.READONLY);
        return earning.listEarnSources(programId);
    }

    @GetMapping("/programs/{programId}/rules")
    public List<EarningRule> listRules(EmployeeIdentity caller, @PathVariable long programId) {
        caller.requireAnyRole(Roles.CAMPAIGN_MANAGER, Roles.READONLY);
        return earning.listRules(programId);
    }

    @PostMapping("/programs/{programId}/rules")
    public ResponseEntity<EarningRule> createRule(EmployeeIdentity caller, @PathVariable long programId,
                                                  @RequestBody RuleCreateRequest req) {
        caller.requireAnyRole(Roles.CAMPAIGN_MANAGER);
        return ResponseEntity.status(HttpStatus.CREATED).body(earning.createRule(caller.userId(), programId, req));
    }

    @PostMapping("/programs/{programId}/rules/{ruleId}/dry-run")
    public DryRunResult dryRun(EmployeeIdentity caller, @PathVariable long programId,
                               @PathVariable long ruleId, @RequestBody RuleDryRunRequest req) {
        caller.requireAnyRole(Roles.CAMPAIGN_MANAGER, Roles.READONLY);
        return earning.dryRun(programId, ruleId, req);
    }

    @PatchMapping("/rules/{ruleId}")
    public EarningRule updateRuleStatus(EmployeeIdentity caller, @PathVariable long ruleId,
                                        @RequestBody RuleStatusRequest req) {
        caller.requireAnyRole(Roles.CAMPAIGN_MANAGER);
        return earning.updateRuleStatus(caller.userId(), ruleId, req);
    }
}
