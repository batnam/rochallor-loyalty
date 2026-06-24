package io.github.batnam.loyalty.core.domain.member;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Pure T&amp;Cs re-acceptance policy (CONTEXT.md "T&amp;Cs Version"). When a Program's current T&amp;Cs
 * version advances beyond what a Member accepted, the Member enters a grace window measured from the
 * instant the current version became effective: during grace they may still EARN but not REDEEM; once
 * the window elapses they are suspended (all features restricted) until they re-accept.
 *
 * <p>A {@code null} accepted version is treated as behind (the Member has never accepted), so it needs
 * re-acceptance just like a stale numeric version.
 */
public final class TcsGracePolicy {

    private TcsGracePolicy() {
    }

    public enum Status {
        /** Member is on the current version — full access. */
        OK,
        /** Behind, within the grace window — may earn, may not redeem. */
        IN_GRACE,
        /** Behind, grace window elapsed — suspend (all features restricted). */
        GRACE_EXPIRED
    }

    public static Status evaluate(Integer acceptedVersion, int currentVersion,
                                  Instant versionEffectiveAt, Instant now, int graceDays) {
        boolean behind = acceptedVersion == null || acceptedVersion < currentVersion;
        if (!behind) {
            return Status.OK;
        }
        Instant graceEnds = versionEffectiveAt.plus(graceDays, ChronoUnit.DAYS);
        return now.isBefore(graceEnds) ? Status.IN_GRACE : Status.GRACE_EXPIRED;
    }

    /** Whether the redeem path must be blocked: behind on T&amp;Cs forbids redeem in both grace states. */
    public static boolean redeemBlocked(Integer acceptedVersion, int currentVersion,
                                         Instant versionEffectiveAt, Instant now, int graceDays) {
        return evaluate(acceptedVersion, currentVersion, versionEffectiveAt, now, graceDays) != Status.OK;
    }
}
