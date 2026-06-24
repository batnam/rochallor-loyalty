package io.github.batnam.loyalty.earning.rule;

import java.util.Optional;

/**
 * Domain-owned port for <b>reading the OPTIONAL Source-Aggregate Cap</b> for a {@code (program, source)}
 * (CONTEXT.md "Source-Aggregate Cap"). Keeps the {@code earn_source_cap} persistence (and the
 * synthetic-counter-id derivation) behind the {@code :infra} seam, mirroring {@link Rules}, so the
 * Rule Engine depends only on {@code :domain} types.
 */
public interface SourceCaps {

    /**
     * The configured Source-Aggregate Cap for {@code (programId, earnSourceCode)}, or empty when none is
     * configured (the common case — the table is seeded empty, so behaviour is unchanged unless a
     * deployment adds a row).
     */
    Optional<SourceCapConfig> findForSource(long programId, String earnSourceCode);
}
