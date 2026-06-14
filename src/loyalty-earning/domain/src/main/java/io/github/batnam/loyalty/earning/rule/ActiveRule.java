package io.github.batnam.loyalty.earning.rule;

import io.github.batnam.loyalty.earning.dsl.RuleDsl;

/**
 * An ACTIVE Earning Rule resolved for the hot path, with its {@code dsl_json} already parsed into the
 * pure {@link RuleDsl} — the JSON deserialization is the {@code :infra} adapter's job, so
 * the {@code :domain} interpreter and the Rule Engine only ever see typed rules.
 *
 * @param ruleId the persisted rule id — used for the Ledger {@code sourceRef} ({@code eventId:ruleId})
 *               and the per-rule cap counter key.
 * @param dsl    the parsed rule grammar to feed {@code DslInterpreter}.
 */
public record ActiveRule(long ruleId, RuleDsl dsl) {
}
