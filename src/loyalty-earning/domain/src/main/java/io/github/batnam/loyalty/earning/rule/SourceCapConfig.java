package io.github.batnam.loyalty.earning.rule;

/**
 * Resolved Source-Aggregate Cap for a {@code (programId, earnSourceCode)} (CONTEXT.md
 * "Source-Aggregate Cap"). Bounds the cumulative points awarded across ALL rules for the source per
 * window. A {@code null} cap field means that window is uncapped.
 *
 * @param counterRuleId the synthetic {@code cap_counter.rule_id} this source's aggregate counter is
 *                      keyed under. Derived from the {@code earn_source_id} as {@code -earnSourceId} so
 *                      it can never collide with a real (positive IDENTITY) rule id. The {@code :infra}
 *                      adapter owns this derivation; the Rule Engine just passes it through to the
 *                      cap machinery.
 */
public record SourceCapConfig(
        long counterRuleId,
        Long dailyCap,
        Long monthlyCap,
        Long lifetimeCap
) {
}
