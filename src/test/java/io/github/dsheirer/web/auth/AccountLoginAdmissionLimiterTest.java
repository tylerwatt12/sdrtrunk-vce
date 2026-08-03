/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AccountLoginAdmissionLimiterTest
{
    @Test
    void boundsGlobalLoginWorkWithFixedCapacityTransientState()
    {
        MutableClock clock = new MutableClock(1_000);
        AccountLoginAdmissionLimiter limiter = new AccountLoginAdmissionLimiter(
            new AccountLoginAdmissionLimiter.Configuration(3, Duration.ofSeconds(10)), clock);

        assertTrue(limiter.tryAcquire().allowed());
        assertTrue(limiter.tryAcquire().allowed());
        assertTrue(limiter.tryAcquire().allowed());
        assertEquals(3, limiter.size());

        AccountLoginAdmissionLimiter.Decision denied = limiter.tryAcquire();
        assertFalse(denied.allowed());
        assertEquals(10_000, denied.retryAfterMillis());
        assertEquals(3, limiter.size(), "denial must not allocate additional state");

        clock.advance(Duration.ofMillis(9_999));
        assertEquals(1, limiter.tryAcquire().retryAfterMillis());
        clock.advance(Duration.ofMillis(1));
        assertTrue(limiter.tryAcquire().allowed());
        assertEquals(1, limiter.size());

        limiter.clear();
        assertEquals(0, limiter.size());
    }

    private static final class MutableClock extends Clock
    {
        private long mMillis;

        private MutableClock(long millis)
        {
            mMillis = millis;
        }

        private void advance(Duration duration)
        {
            mMillis += duration.toMillis();
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return Instant.ofEpochMilli(mMillis);
        }
    }
}
