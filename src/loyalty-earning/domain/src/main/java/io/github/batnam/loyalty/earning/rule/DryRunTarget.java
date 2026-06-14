package io.github.batnam.loyalty.earning.rule;

import io.github.batnam.loyalty.earning.dsl.RuleDsl;

/**
 * A single Earning Rule resolved for a dry-run replay: the parsed {@link RuleDsl} plus the
 * {@code earnSourceCode} its historical events arrive under, so the dry-run evaluator can pull the
 * matching window from the replay store without itself touching the JPA model or parsing JSON.
 *
 * @param ruleId         the rule being replayed.
 * @param earnSourceCode the code events for this rule's source carry in the replay store.
 * @param dsl            the parsed rule grammar to feed {@code DslInterpreter}.
 */
public record DryRunTarget(long ruleId, String earnSourceCode, RuleDsl dsl) {
}
