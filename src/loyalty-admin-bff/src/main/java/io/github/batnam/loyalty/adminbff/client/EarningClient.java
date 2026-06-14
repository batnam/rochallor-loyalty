package io.github.batnam.loyalty.adminbff.client;

import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.DryRunResult;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.EarnSource;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.EarningRule;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.RuleCreateRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.RuleDryRunRequest;
import io.github.batnam.loyalty.adminbff.api.dto.AdminDtos.RuleStatusRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Anti-Corruption boundary to loyalty-earning: the Earn Source registry and Earning Rule authoring
 * (create DRAFT, dry-run, activate/archive). Activation (→ ACTIVE) is approval-gated upstream — earning
 * enforces the gate; the BFF forwards the {@code X-Actor} for the audit trail.
 */
@Component
public class EarningClient {

    private final RestClient earning;

    public EarningClient(@Qualifier("earningRestClient") RestClient earningRestClient) {
        this.earning = earningRestClient;
    }

    public List<EarnSource> listEarnSources(long programId) {
        return earning.get()
                .uri("/programs/{programId}/earn-sources", programId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public List<EarningRule> listRules(long programId) {
        return earning.get()
                .uri("/programs/{programId}/rules", programId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public EarningRule createRule(String actor, long programId, RuleCreateRequest req) {
        return earning.post()
                .uri("/programs/{programId}/rules", programId)
                .header("X-Actor", actor)
                .body(req)
                .retrieve()
                .body(EarningRule.class);
    }

    public DryRunResult dryRun(long programId, long ruleId, RuleDryRunRequest req) {
        return earning.post()
                .uri("/programs/{programId}/rules/{ruleId}/dry-run", programId, ruleId)
                .body(req)
                .retrieve()
                .body(DryRunResult.class);
    }

    public EarningRule updateRuleStatus(String actor, long ruleId, RuleStatusRequest req) {
        return earning.patch()
                .uri("/rules/{ruleId}", ruleId)
                .header("X-Actor", actor)
                .body(req)
                .retrieve()
                .body(EarningRule.class);
    }
}
