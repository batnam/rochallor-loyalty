package io.github.batnam.loyalty.bridge.velocity;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-{@code customerId} sliding window of earn-event timestamps.
 * Ephemeral and rebuildable: on Pod restart the window is reconstructed by replaying the earn
 * stream (no RDS). Pruned to {@code window} on every record. Thread-safe per customer.
 */
@Component
public class SlidingWindowCounterStore {

    private final Map<Long, Deque<Instant>> windows = new ConcurrentHashMap<>();

    /** Record an event and return the number of events now within {@code window} ending at {@code now}. */
    public int recordAndCount(long customerId, Instant eventAt, Duration window, Instant now) {
        Deque<Instant> dq = windows.computeIfAbsent(customerId, k -> new ArrayDeque<>());
        Instant cutoff = now.minus(window);
        synchronized (dq) {
            dq.addLast(eventAt);
            while (!dq.isEmpty() && dq.peekFirst().isBefore(cutoff)) {
                dq.pollFirst();
            }
            return dq.size();
        }
    }
}
