package io.github.batnam.loyalty.core.domain.member;

/** Member lifecycle status (CONTEXT.md "Member"). Pure domain copy; the persistence model maps it by name. */
public enum MemberStatus {
    ACTIVE, SUSPENDED_TCS, OPTED_OUT, CLOSED
}
