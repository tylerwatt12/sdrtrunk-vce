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

class LoginThrottleTest
{
    @Test
    void capsTrackedSourcesAndExpiresFailureState()
    {
        MutableClock clock = new MutableClock();
        LoginThrottle throttle = new LoginThrottle(new LoginThrottle.Configuration(2, 2,
            Duration.ofMinutes(1), Duration.ofMinutes(2)), clock);
        assertTrue(throttle.check("source-one").allowed());
        throttle.recordFailure("source-one");
        assertTrue(throttle.check("source-one").allowed());
        throttle.recordFailure("source-one");
        assertFalse(throttle.check("source-one").allowed());
        throttle.recordFailure("source-two");
        assertEquals(2, throttle.size());
        assertFalse(throttle.check("source-three").allowed(), "untracked sources fail closed at the memory cap");

        throttle.recordSuccess("source-two");
        assertTrue(throttle.check("source-three").allowed());
        clock.advance(Duration.ofMinutes(3));
        assertTrue(throttle.check("source-one").allowed());
        assertEquals(0, throttle.size());
    }

    private static final class MutableClock extends Clock
    {
        private long mMillis = 1_000;

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

        @Override
        public long millis()
        {
            return mMillis;
        }
    }
}
