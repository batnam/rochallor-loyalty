package io.github.batnam.loyalty.earning.source;

/** Earning Rule lifecycle (loyalty-earning.yaml). DRAFT → ACTIVE → ARCHIVED; never deleted (audit). */
public enum RuleStatus {
    DRAFT, ACTIVE, ARCHIVED
}
