package com.acme.shortener;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key token bucket. The key table is bounded so a flood of distinct clients cannot exhaust memory.
 *
 * @concepts rate-limit throttling abuse client-ip
 */
public final class RateLimiter {
    private static final int MAX_KEYS = 100_000;

    private static final class Bucket {
        double tokens;
        long last;
    }

    private final int capacity;
    private final double refillPerMilli;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int capacity, int refillPerMinute, Clock clock) {
        if (capacity <= 0 || refillPerMinute <= 0) {
            throw new IllegalArgumentException("capacity and refill must be positive");
        }
        this.capacity = capacity;
        this.refillPerMilli = refillPerMinute / 60_000.0;
        this.clock = clock;
    }

    public boolean tryAcquire(String key) {
        long now = clock.millis();
        if (buckets.size() >= MAX_KEYS) {
            buckets.clear();
        }
        Bucket b = buckets.computeIfAbsent(key, k -> {
            Bucket nb = new Bucket();
            nb.tokens = capacity;
            nb.last = now;
            return nb;
        });
        synchronized (b) {
            b.tokens = Math.min(capacity, b.tokens + Math.max(0, now - b.last) * refillPerMilli);
            b.last = now;
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    public long retryAfterSeconds() {
        return Math.max(1, (long) Math.ceil(1.0 / (refillPerMilli * 1000.0)));
    }
}
