package com.agentmesh.deterministic.a2a;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TokenBucketRateLimiter {
    private final int limitPerMinute;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int limitPerMinute) {
        this(limitPerMinute, Clock.systemUTC());
    }

    TokenBucketRateLimiter(int limitPerMinute, Clock clock) {
        this.limitPerMinute = Math.max(1, limitPerMinute);
        this.clock = clock;
    }

    public boolean tryAcquire(String key) {
        long minute = clock.millis() / 60_000L;
        Window window = windows.compute(key, (ignored, existing) -> {
            if (existing == null || existing.minute != minute) {
                return new Window(minute, 1);
            }
            return new Window(existing.minute, existing.count + 1);
        });
        return window.count <= limitPerMinute;
    }

    private record Window(long minute, int count) {
    }
}
