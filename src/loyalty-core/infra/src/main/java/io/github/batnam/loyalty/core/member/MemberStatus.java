package io.github.batnam.loyalty.core.member;

/** Member lifecycle status (CONTEXT.md "Member", Member State Machine). */
public enum MemberStatus {
    ACTIVE, SUSPENDED_TCS, OPTED_OUT, CLOSED
}
