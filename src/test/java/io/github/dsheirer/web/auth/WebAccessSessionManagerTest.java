/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebAccessSessionManagerTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void boundsSessionsAndAppliesIdleAbsoluteCsrfAndCredentialRules() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        WebAccessAccount account = accessService.provisionOrResetPrimaryAdmin(
            "primary admin password".toCharArray());
        MutableClock clock = new MutableClock(1_000);
        WebAccessSessionManager.Configuration configuration = new WebAccessSessionManager.Configuration(2,
            Duration.ofMinutes(5), Duration.ofMinutes(10), 32);

        try(WebAccessSessionManager manager =
                new WebAccessSessionManager(configuration, new SecureRandom(), clock))
        {
            WebAccessSession first = manager.create(account).orElseThrow();
            WebAccessSession second = manager.create(account).orElseThrow();
            assertTrue(manager.create(account).isEmpty());
            assertEquals(2, manager.getActiveSessionCount());
            assertTrue(manager.validateCsrf(first.sessionId(), first.csrfToken(), accessService));
            assertFalse(manager.validateCsrf(first.sessionId(), second.csrfToken(), accessService));
            assertTrue(manager.resolve("x".repeat(10_000), accessService).isEmpty());
            assertFalse(manager.validateCsrf(first.sessionId(), "x".repeat(10_000), accessService));
            assertFalse(first.toString().contains(first.sessionId()));
            assertFalse(first.toString().contains(first.csrfToken()));

            clock.advance(Duration.ofMinutes(4));
            assertTrue(manager.resolve(first.sessionId(), accessService).isPresent());
            clock.advance(Duration.ofMinutes(2));
            assertTrue(manager.resolve(second.sessionId(), accessService).isEmpty(),
                "second session should expire while idle");
            assertTrue(manager.resolve(first.sessionId(), accessService).isPresent());
            clock.advance(Duration.ofMinutes(5));
            assertTrue(manager.resolve(first.sessionId(), accessService).isEmpty(),
                "absolute expiry cannot be extended");

            WebAccessAccount current = accessService.primaryAdmin().orElseThrow();
            WebAccessSession revoked = manager.create(current).orElseThrow();
            accessService.provisionOrResetPrimaryAdmin("replacement admin password".toCharArray());
            assertTrue(manager.resolve(revoked.sessionId(), accessService).isEmpty(),
                "credential-version change must revoke the old session");
            assertEquals(0, manager.getActiveSessionCount());
        }
    }

    @Test
    void ordinaryAccountsCannotConsumeThePrimaryAdministratorReserve() throws Exception
    {
        Path database = mTemporaryFolder.resolve("reserved-sessions.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        WebAccessAccount primary = accessService.provisionOrResetPrimaryAdmin(
            "primary admin password".toCharArray());
        WebAccessAccount user = accessService.createUser("listener", "listener password".toCharArray(),
            AccessTier.USER);
        WebAccessSessionManager.Configuration configuration = new WebAccessSessionManager.Configuration(4,
            Duration.ofMinutes(5), Duration.ofMinutes(10), 32);

        try(WebAccessSessionManager manager = new WebAccessSessionManager(configuration))
        {
            assertTrue(manager.create(user).isPresent());
            assertTrue(manager.create(user).isPresent());
            assertTrue(manager.create(user).isEmpty(),
                "ordinary accounts must not consume the primary administrator reserve");
            assertTrue(manager.create(primary).isPresent());
            assertTrue(manager.create(primary).isPresent());
            assertEquals(4, manager.getActiveSessionCount());
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
