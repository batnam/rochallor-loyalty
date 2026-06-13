package io.github.batnam.loyalty.earning.rule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Domain-owned port for <b>reading Earning Rules for evaluation</b> (ADR-0001 "Onion Architecture").
 * Both consumers — the Rule Engine hot path and the dry-run evaluator — want rules whose {@code dsl_json}
 * has already been parsed into the pure {@link io.github.batnam.loyalty.earning.dsl.RuleDsl}. Keeping the
 * JSON deserialization (and the {@code earn_source} catalogue join) behind this seam means the
 * {@code :app} evaluation services depend only on {@code :domain} types, never on Spring Data or Jackson.
 *
 * <p>This is the <i>evaluation</i> seam only — the authoring path (creating/transitioning rules over the
 * raw {@code dsl_json} config rows) is not an aggregate and stays on the JPA repositories directly.
 */
public interface Rules {

    /**
     * Hot-path resolution: every ACTIVE rule for {@code (programId, earnSourceCode)} whose validity
     * window contains {@code now}, DSL already parsed. An unknown source code yields an empty list.
     * Multiple may match — each fires and contributes its own Ledger entry (CONTEXT.md "Conflict" = sum).
     */
    List<ActiveRule> findActiveForSource(long programId, String earnSourceCode, Instant now);

    /**
     * Dry-run resolution: a single rule by id, parsed, carrying the {@code earnSourceCode} its replayed
     * events arrive under. Empty if no such rule exists.
     */
    Optional<DryRunTarget> findForDryRun(long ruleId);
}
