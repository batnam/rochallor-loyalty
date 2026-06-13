package io.github.batnam.loyalty.earning.source;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.batnam.loyalty.earning.audit.AuditLogWriter;
import io.github.batnam.loyalty.earning.dsl.DslValidator;
import io.github.batnam.loyalty.earning.error.EarningException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The Earn Source Registry (L3 §4 component 3) — the only component that authors Earning Rules. On
 * create it runs the {@link DslValidator} schema gate (loyalty-earning.yaml: dslJson is validated at
 * save), persists a DRAFT, and writes an audit row. Status transitions are approval-gated: → ACTIVE
 * requires a {@code bepApprovalRef} (proof BEP approved the economic change); → ARCHIVED applies
 * directly. Every write is hash-chain audited.
 */
@Service
public class EarnSourceRegistry {

    private final EarnSourceRepository sources;
    private final RuleRepository rules;
    private final DslValidator validator;
    private final AuditLogWriter audit;

    public EarnSourceRegistry(EarnSourceRepository sources, RuleRepository rules,
                              DslValidator validator, AuditLogWriter audit) {
        this.sources = sources;
        this.rules = rules;
        this.validator = validator;
        this.audit = audit;
    }

    public List<EarnSource> listSources() {
        return sources.findAll();
    }

    public EarningRule getRule(long ruleId) {
        return rules.findById(ruleId)
                .orElseThrow(() -> EarningException.notFound("RULE_NOT_FOUND", "rule " + ruleId + " not found"));
    }

    public List<EarningRule> listRules(long programId, RuleStatus status, Long earnSourceId) {
        List<EarningRule> result = (status != null)
                ? rules.findByProgramIdAndStatus(programId, status)
                : rules.findByProgramId(programId);
        if (earnSourceId != null) {
            result = result.stream().filter(r -> earnSourceId.equals(r.getEarnSourceId())).toList();
        }
        return result;
    }

    @Transactional
    public EarningRule createRule(String actor, long programId, Long earnSourceId, JsonNode dslJson,
                                  Instant effectiveFrom, Instant effectiveTo, Long campaignId) {
        sources.findById(earnSourceId)
                .orElseThrow(() -> EarningException.badRequest("EARN_SOURCE_UNKNOWN",
                        "earnSourceId " + earnSourceId + " is not in the catalogue"));

        List<String> errors = validator.validate(dslJson);
        if (!errors.isEmpty()) {
            throw EarningException.badRequest("DSL_INVALID", "DSL failed schema validation: " + errors);
        }

        EarningRule rule = rules.save(EarningRule.draft(
                programId, earnSourceId, dslJson.toString(), effectiveFrom, effectiveTo, campaignId));
        audit.record(actor, "RuleCreated", "earning_rule", String.valueOf(rule.getRuleId()),
                null, dslJson.toString());
        return rule;
    }

    @Transactional
    public EarningRule transitionStatus(String actor, long ruleId, RuleStatus target, String bepApprovalRef) {
        EarningRule rule = getRule(ruleId);
        String before = rule.getStatus().name();
        switch (target) {
            case ACTIVE -> {
                if (bepApprovalRef == null || bepApprovalRef.isBlank()) {
                    throw EarningException.conflict("APPROVAL_REQUIRED",
                            "activation requires a bepApprovalRef (approval-gated economic change)");
                }
                rule.activate();
            }
            case ARCHIVED -> rule.archive();
            case DRAFT -> throw EarningException.conflict("ILLEGAL_TRANSITION", "cannot transition back to DRAFT");
        }
        rules.save(rule);
        audit.record(actor, "RuleStatus:" + target, "earning_rule", String.valueOf(ruleId), before, target.name());
        return rule;
    }
}
