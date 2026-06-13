package io.github.batnam.loyalty.earning.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.batnam.loyalty.earning.dsl.DslParser;
import io.github.batnam.loyalty.earning.dsl.RuleDsl;
import io.github.batnam.loyalty.earning.rule.ActiveRule;
import io.github.batnam.loyalty.earning.rule.DryRunTarget;
import io.github.batnam.loyalty.earning.rule.Rules;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA-backed adapter for the {@link Rules} port (ADR-0001). Bridges the {@code earning_rule} /
 * {@code earn_source} persistence model to the pure evaluation types: it resolves the {@code earn_source}
 * catalogue, runs the hot-path query on {@link RuleRepository}, and — crucially — does the
 * {@code dsl_json} → {@link RuleDsl} parse here (Jackson + {@link DslParser}) so neither the
 * {@code :domain} interpreter nor the {@code :app} evaluation services ever touch JSON or Spring Data.
 */
@Component
public class JpaRules implements Rules {

    private final RuleRepository rules;
    private final EarnSourceRepository sources;
    private final ObjectMapper mapper;

    public JpaRules(RuleRepository rules, EarnSourceRepository sources, ObjectMapper mapper) {
        this.rules = rules;
        this.sources = sources;
        this.mapper = mapper;
    }

    @Override
    public List<ActiveRule> findActiveForSource(long programId, String earnSourceCode, Instant now) {
        Optional<EarnSource> source = sources.findByEarnSourceCode(earnSourceCode);
        if (source.isEmpty()) {
            return List.of();
        }
        return rules.findActiveForSource(programId, source.get().getEarnSourceId(), now).stream()
                .map(r -> new ActiveRule(r.getRuleId(), parse(r)))
                .toList();
    }

    @Override
    public Optional<DryRunTarget> findForDryRun(long ruleId) {
        return rules.findById(ruleId).flatMap(rule ->
                sources.findById(rule.getEarnSourceId())
                        .map(src -> new DryRunTarget(rule.getRuleId(), src.getEarnSourceCode(), parse(rule))));
    }

    private RuleDsl parse(EarningRule rule) {
        try {
            JsonNode node = mapper.readTree(rule.getDslJson());
            return DslParser.parse(node);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt dsl_json for rule " + rule.getRuleId(), e);
        }
    }
}
