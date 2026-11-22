package com.smartchef.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {

    /** Reloj que avanza a mano. */
    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-11-22T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("Deja pasar hasta el maximo y rechaza el siguiente con Retry-After")
    void rejectsAboveMax() {
        ManualClock clock = new ManualClock();
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, Duration.ofMinutes(10), clock);

        assertThat(limiter.tryAcquire("1.1.1.1").allowed()).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1").allowed()).isTrue();
        FixedWindowRateLimiter.Decision third = limiter.tryAcquire("1.1.1.1");

        assertThat(third.allowed()).isFalse();
        assertThat(third.retryAfterSeconds()).isEqualTo(600);
        assertThat(limiter.tryAcquire("2.2.2.2").allowed()).isTrue();
    }

    @Test
    @DisplayName("La ventana se renueva al vencer")
    void resetsAfterWindow() {
        ManualClock clock = new ManualClock();
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, Duration.ofMinutes(1), clock);

        limiter.tryAcquire("k");
        assertThat(limiter.tryAcquire("k").allowed()).isFalse();

        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
    }

    @Test
    @DisplayName("check no consume cupo y reset lo devuelve")
    void checkAndReset() {
        ManualClock clock = new ManualClock();
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, Duration.ofMinutes(1), clock);

        assertThat(limiter.check("k").allowed()).isTrue();
        limiter.tryAcquire("k");
        assertThat(limiter.check("k").allowed()).isFalse();

        limiter.reset("k");
        assertThat(limiter.check("k").allowed()).isTrue();
    }
}
