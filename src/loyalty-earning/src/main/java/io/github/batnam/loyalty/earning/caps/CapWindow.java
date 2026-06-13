package io.github.batnam.loyalty.earning.caps;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * A per-member cap bucket (CONTEXT.md "Cap"; earning-rule.schema.json {@code caps}). The {@link #keyFor}
 * value scopes one {@code cap_counter} row — events in the same UTC day/month accumulate against the
 * same counter, so the conditional decrement enforces the cap across them.
 */
public enum CapWindow {

    /** {@code perMemberPerDay} — one bucket per UTC calendar day. */
    DAY {
        @Override public String keyFor(Instant at) { return "DAY:" + DAY_FMT.format(at); }
        @Override public Instant expiresAt(Instant at) { return at.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant(); }
    },

    /** {@code perMemberPerMonth} — one bucket per UTC calendar month. */
    MONTH {
        @Override public String keyFor(Instant at) { return "MONTH:" + MONTH_FMT.format(at); }
        @Override public Instant expiresAt(Instant at) { return at.atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).plusMonths(2).atStartOfDay(ZoneOffset.UTC).toInstant(); }
    },

    /** {@code perMemberPerRule} — a single lifetime bucket (never purged). */
    LIFE {
        @Override public String keyFor(Instant at) { return "LIFE"; }
        @Override public Instant expiresAt(Instant at) { return null; }
    };

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    /** The {@code cap_counter.window_key} for an event occurring at {@code at}. */
    public abstract String keyFor(Instant at);

    /** When this window's counter may be purged ({@code null} for {@link #LIFE}). */
    public abstract Instant expiresAt(Instant at);
}
