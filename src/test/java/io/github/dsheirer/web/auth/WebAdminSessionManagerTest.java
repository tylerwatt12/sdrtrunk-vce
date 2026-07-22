/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WebAdminSessionManagerTest
{
    @Test
    void boundsSessionsAndAppliesIdleAbsoluteCsrfAndGenerationRules()
    {
        MutableClock clock = new MutableClock(1_000);
        WebAdminSessionManager.Configuration configuration = new WebAdminSessionManager.Configuration(2,
            Duration.ofMinutes(5), Duration.ofMinutes(10), 32);

        try(WebAdminSessionManager manager = new WebAdminSessionManager(configuration, new SecureRandom(), clock))
        {
            WebAdminSession first = manager.create(1).orElseThrow();
            WebAdminSession second = manager.create(1).orElseThrow();
            assertTrue(manager.create(1).isEmpty());
            assertEquals(2, manager.getActiveSessionCount());
            assertTrue(manager.validateCsrf(first.sessionId(), first.csrfToken(), 1));
            assertFalse(manager.validateCsrf(first.sessionId(), second.csrfToken(), 1));
            assertFalse(manager.resolve("x".repeat(10_000), 1).isPresent());
            assertFalse(manager.validateCsrf(first.sessionId(), "x".repeat(10_000), 1));
            assertFalse(first.toString().contains(first.sessionId()));
            assertFalse(first.toString().contains(first.csrfToken()));

            clock.advance(Duration.ofMinutes(4));
            assertTrue(manager.resolve(first.sessionId(), 1).isPresent());
            clock.advance(Duration.ofMinutes(2));
            assertFalse(manager.resolve(second.sessionId(), 1).isPresent(), "second session should expire while idle");
            assertTrue(manager.resolve(first.sessionId(), 1).isPresent());
            clock.advance(Duration.ofMinutes(5));
            assertFalse(manager.resolve(first.sessionId(), 1).isPresent(), "absolute expiry cannot be extended");

            WebAdminSession nextGeneration = manager.create(2).orElseThrow();
            assertTrue(manager.resolve(nextGeneration.sessionId(), 1).isEmpty());
            assertEquals(0, manager.getActiveSessionCount());
        }
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

        @Override
        public long millis()
        {
            return mMillis;
        }
    }
}
