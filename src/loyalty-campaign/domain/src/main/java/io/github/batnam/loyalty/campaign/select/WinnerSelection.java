package io.github.batnam.loyalty.campaign.select;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/**
 * The Winner Selection algorithm (L3 §3.3) — pure, deterministic and audit-replayable.
 *
 * <p>For {@code SEEDED_RNG}/{@code WEIGHTED} the seed is {@code HMAC-SHA256(secret, drawingId|drawAt)}.
 * The HMAC secret is the only non-public input: with it, plus the immutable {@code entry_id} order and the
 * frozen per-entry weights, an auditor reproduces the exact winners; without it, an attacker watching
 * public state cannot predict them. {@code FIRST_N} uses no seed — winners are the first K by arrival.
 *
 * <p>The draw itself is a partial Fisher–Yates (uniform) or cumulative-weight (weighted) selection without
 * replacement, driven by a {@link Random} seeded from the HMAC's leading 8 bytes. {@code Random} is a
 * deterministic PRNG — adequate here because unpredictability comes from the secret seed, not the PRNG;
 * the same seed always yields the same draw, which is exactly what audit replay needs.
 *
 * <p>{@code winnerIndex} is the chosen entry's position in the {@code entry_id}-ordered set, so the K
 * winners carry K distinct indices ({@code UNIQUE(drawing_id, winner_index)} holds by construction).
 */
public final class WinnerSelection {

    private WinnerSelection() {
    }

    /** One eligible entry. {@code index} is its 0-based position in the {@code entry_id}-ordered set. */
    public record Entry(long memberId, int index, int weight) {
    }

    /** One selected winner. {@code seedHex} is null for {@code FIRST_N}. */
    public record Winner(long memberId, int winnerIndex, String seedHex) {
    }

    /**
     * Pick {@code K = min(winnersCount, entries.size())} winners without replacement.
     *
     * @param entriesByEntryId entries in immutable {@code entry_id} order (index 0..N-1)
     */
    public static List<Winner> select(long drawingId, Instant drawAt, String hmacSecret,
                                      List<Entry> entriesByEntryId, SelectionStrategy strategy,
                                      int winnersCount) {
        int n = entriesByEntryId.size();
        int k = Math.min(winnersCount, n);
        if (k <= 0) {
            return List.of();
        }
        if (strategy == SelectionStrategy.FIRST_N) {
            List<Winner> winners = new ArrayList<>(k);
            for (int i = 0; i < k; i++) {
                Entry e = entriesByEntryId.get(i);
                winners.add(new Winner(e.memberId(), e.index(), null));   // no seed for arrival order
            }
            return List.copyOf(winners);
        }

        String seedHex = seedHex(drawingId, drawAt, hmacSecret);
        Random rng = new Random(longSeed(seedHex));
        List<Entry> pool = new ArrayList<>(entriesByEntryId);             // mutable working copy
        List<Winner> winners = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            int picked = strategy == SelectionStrategy.WEIGHTED ? pickWeighted(pool, rng) : rng.nextInt(pool.size());
            Entry e = pool.remove(picked);                               // without replacement
            winners.add(new Winner(e.memberId(), e.index(), seedHex));
        }
        return List.copyOf(winners);
    }

    /** Cumulative-weight pick: returns the index in {@code pool} of the entry the draw landed on. */
    private static int pickWeighted(List<Entry> pool, Random rng) {
        long total = 0;
        for (Entry e : pool) {
            total += e.weight();
        }
        long r = Math.floorMod(rng.nextLong(), total);   // uniform in [0, total)
        long acc = 0;
        for (int i = 0; i < pool.size(); i++) {
            acc += pool.get(i).weight();
            if (r < acc) {
                return i;
            }
        }
        return pool.size() - 1;   // unreachable unless rounding; defensive
    }

    /** {@code HMAC-SHA256(secret, "drawingId|drawAt")} as lowercase hex (the audit-stored seed). */
    private static String seedHex(long drawingId, Instant drawAt, String hmacSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] message = (drawingId + "|" + drawAt.toString()).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable for winner-selection seed", e);
        }
    }

    /** Fold the seed's leading 8 bytes into the long the PRNG is seeded with. */
    private static long longSeed(String seedHex) {
        byte[] b = HexFormat.of().parseHex(seedHex.substring(0, 16));
        long s = 0;
        for (byte value : b) {
            s = (s << 8) | (value & 0xff);
        }
        return s;
    }
}
