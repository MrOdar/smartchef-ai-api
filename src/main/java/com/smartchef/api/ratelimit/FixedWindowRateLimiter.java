package com.smartchef.api.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contador de ventana fija por clave, en memoria y seguro entre hilos.
 *
 * <p>Las ventanas vencidas se purgan cuando el mapa crece, para que una lluvia
 * de claves distintas (IPs o usuarios inventados) no agote la memoria.
 */
public final class FixedWindowRateLimiter {

    private static final int PURGE_THRESHOLD = 10_000;

    private final int max;
    private final long windowMs;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int max, Duration window, Clock clock) {
        if (max <= 0 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("El limite necesita max > 0 y una ventana positiva");
        }
        this.max = max;
        this.windowMs = window.toMillis();
        this.clock = clock;
    }

    /** Consume una unidad si queda cupo. */
    public Decision tryAcquire(String key) {
        long now = clock.millis();
        purgeIfLarge(now);
        Window window = windows.compute(key, (k, current) ->
                current == null || current.expired(now) ? new Window(now + windowMs, 1) : current.increment());
        return window.count() <= max ? Decision.ALLOWED : Decision.rejected(window.retryAfterSeconds(now));
    }

    /** Comprueba si queda cupo sin consumirlo. */
    public Decision check(String key) {
        long now = clock.millis();
        Window window = windows.get(key);
        if (window == null || window.expired(now) || window.count() < max) {
            return Decision.ALLOWED;
        }
        return Decision.rejected(window.retryAfterSeconds(now));
    }

    /** Olvida la clave, p. ej. tras un login correcto. */
    public void reset(String key) {
        windows.remove(key);
    }

    private void purgeIfLarge(long now) {
        if (windows.size() > PURGE_THRESHOLD) {
            windows.values().removeIf(window -> window.expired(now));
        }
    }

    private record Window(long resetAt, int count) {
        boolean expired(long now) {
            return now >= resetAt;
        }

        Window increment() {
            return new Window(resetAt, count == Integer.MAX_VALUE ? count : count + 1);
        }

        long retryAfterSeconds(long now) {
            return Math.max(1, (resetAt - now + 999) / 1000);
        }
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);

        static Decision rejected(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }
}
