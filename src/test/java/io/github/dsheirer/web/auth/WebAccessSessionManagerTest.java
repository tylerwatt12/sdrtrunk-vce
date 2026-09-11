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
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebAccessSessionManagerTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void sessionsHaveNoTimeMetadataAndRemainValidUntilCredentialRevocation() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        WebAccessAccount account = accessService.provisionOrResetPrimaryAdmin(
            "primary admin password".toCharArray());
        WebAccessSessionManager.Configuration configuration = new WebAccessSessionManager.Configuration(2, 32);

        assertEquals(List.of("sessionId", "csrfToken", "account"),
            Arrays.stream(WebAccessSession.class.getRecordComponents()).map(component -> component.getName()).toList());
        assertEquals(List.of("maximumSessions", "tokenBytes"),
            Arrays.stream(WebAccessSessionManager.Configuration.class.getRecordComponents())
                .map(component -> component.getName()).toList());
        assertEquals(64, WebAccessSessionManager.Configuration.defaults().maximumSessions());
        assertEquals(32, WebAccessSessionManager.Configuration.defaults().tokenBytes());

        try(WebAccessSessionManager manager = new WebAccessSessionManager(configuration))
        {
            WebAccessSession first = manager.create(account).orElseThrow();
            WebAccessSession second = manager.create(account).orElseThrow();
            assertEquals(2, manager.getActiveSessionCount());
            assertTrue(manager.validateCsrf(first.sessionId(), first.csrfToken(), accessService));
            assertFalse(manager.validateCsrf(first.sessionId(), second.csrfToken(), accessService));
            assertTrue(manager.resolve("x".repeat(10_000), accessService).isEmpty());
            assertFalse(manager.validateCsrf(first.sessionId(), "x".repeat(10_000), accessService));
            assertFalse(first.toString().contains(first.sessionId()));
            assertFalse(first.toString().contains(first.csrfToken()));

            for(int resolution = 0; resolution < 100; resolution++)
            {
                assertEquals(first, manager.resolve(first.sessionId(), accessService).orElseThrow(),
                    "a current session must not expire as it is resolved");
            }

            accessService.provisionOrResetPrimaryAdmin("replacement admin password".toCharArray());
            assertTrue(manager.resolve(first.sessionId(), accessService).isEmpty(),
                "authentication-revision change must revoke the old session");
            assertTrue(manager.resolve(second.sessionId(), accessService).isEmpty(),
                "credential revocation must apply to every session for the account");
            assertEquals(0, manager.getActiveSessionCount());
        }
    }

    @Test
    void oneAccountCanCreateMoreThanEightSessionsBelowGlobalCapacity() throws Exception
    {
        Path database = mTemporaryFolder.resolve("more-than-eight-sessions.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        WebAccessAccount primary = accessService.provisionOrResetPrimaryAdmin(
            "primary admin password".toCharArray());
        WebAccessSessionManager.Configuration configuration = new WebAccessSessionManager.Configuration(16, 32);

        try(WebAccessSessionManager manager = new WebAccessSessionManager(configuration))
        {
            WebAccessSession first = manager.create(primary).orElseThrow();

            for(int session = 1; session < 9; session++)
            {
                assertTrue(manager.create(primary).isPresent());
            }

            assertEquals(9, manager.getActiveSessionCount());
            assertTrue(manager.resolve(first.sessionId(), accessService).isPresent(),
                "creating a ninth session for one account must not replace an earlier session below global capacity");
        }
    }

    @Test
    void replacesTheLeastRecentlyUsedSameAccountSessionAndReusesACurrentCookie() throws Exception
    {
        Path database = mTemporaryFolder.resolve("same-account-lru.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        WebAccessAccount primary = accessService.provisionOrResetPrimaryAdmin(
            "primary admin password".toCharArray());
        WebAccessSessionManager.Configuration configuration = new WebAccessSessionManager.Configuration(3, 32);

        try(WebAccessSessionManager manager = new WebAccessSessionManager(configuration))
        {
            WebAccessSession first = manager.create(primary).orElseThrow();
            WebAccessSession second = manager.create(primary).orElseThrow();
            WebAccessSession third = manager.create(primary).orElseThrow();

            WebAccessSession reused =
                manager.createOrReuseAtCapacity(primary, first.sessionId()).orElseThrow();
            assertEquals(first, reused, "a matching current cookie must be reused at capacity");
            assertEquals(3, manager.getActiveSessionCount());
            assertTrue(manager.resolve(second.sessionId(), accessService).isPresent());
            assertTrue(manager.resolve(third.sessionId(), accessService).isPresent());

            //Make the first session most recently used, leaving the second as the access-ordered replacement.
            assertTrue(manager.resolve(first.sessionId(), accessService).isPresent());
            assertTrue(manager.resolve(third.sessionId(), accessService).isPresent());
            WebAccessSession replacement = manager.create(primary).orElseThrow();

            assertTrue(manager.resolve(second.sessionId(), accessService).isEmpty(),
                "a cookie-less login must replace the least-recently-used session for the same account");
            assertTrue(manager.resolve(first.sessionId(), accessService).isPresent());
            assertTrue(manager.resolve(third.sessionId(), accessService).isPresent());
            assertTrue(manager.resolve(replacement.sessionId(), accessService).isPresent());
            assertEquals(3, manager.getActiveSessionCount());
        }
    }

    @Test
    void ordinarySessionsRotateWithoutConsumingThePrimaryAdministratorReserve() throws Exception
    {
        Path database = mTemporaryFolder.resolve("reserved-sessions.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        WebAccessAccount primary = accessService.provisionOrResetPrimaryAdmin(
            "primary admin password".toCharArray());
        WebAccessAccount user = accessService.createUser("listener", "listener password".toCharArray(),
            AccessTier.USER);
        WebAccessSessionManager.Configuration configuration = new WebAccessSessionManager.Configuration(4, 32);

        try(WebAccessSessionManager manager = new WebAccessSessionManager(configuration))
        {
            WebAccessSession firstOrdinary = manager.create(user).orElseThrow();
            WebAccessSession secondOrdinary = manager.create(user).orElseThrow();
            WebAccessSession rotatedOrdinary = manager.create(user).orElseThrow();
            assertTrue(manager.resolve(firstOrdinary.sessionId(), accessService).isEmpty());
            assertTrue(manager.resolve(secondOrdinary.sessionId(), accessService).isPresent());
            assertTrue(manager.resolve(rotatedOrdinary.sessionId(), accessService).isPresent());
            assertEquals(2, manager.getActiveSessionCount(),
                "ordinary-session rotation must leave the administrator reserve unused");

            WebAccessSession firstPrimary = manager.create(primary).orElseThrow();
            WebAccessSession secondPrimary = manager.create(primary).orElseThrow();
            assertEquals(4, manager.getActiveSessionCount());

            WebAccessSession nextOrdinary = manager.create(user).orElseThrow();
            assertTrue(manager.resolve(secondOrdinary.sessionId(), accessService).isEmpty());
            assertTrue(manager.resolve(rotatedOrdinary.sessionId(), accessService).isPresent());
            assertTrue(manager.resolve(nextOrdinary.sessionId(), accessService).isPresent());
            assertTrue(manager.resolve(firstPrimary.sessionId(), accessService).isPresent());
            assertTrue(manager.resolve(secondPrimary.sessionId(), accessService).isPresent());
            assertEquals(4, manager.getActiveSessionCount(),
                "ordinary-session rotation must never evict either reserved administrator session");
        }
    }

    @Test
    void surplusPrimarySessionsYieldToOrdinaryCapacityWhileTheReserveRemainsProtected() throws Exception
    {
        Path database = mTemporaryFolder.resolve("surplus-primary-sessions.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        WebAccessAccount primary = accessService.provisionOrResetPrimaryAdmin(
            "primary admin password".toCharArray());
        WebAccessAccount firstUser = accessService.createUser("listener-one", "listener one password".toCharArray(),
            AccessTier.USER);
        WebAccessAccount secondUser = accessService.createUser("listener-two", "listener two password".toCharArray(),
            AccessTier.USER);
        WebAccessAccount thirdUser = accessService.createUser("listener-three", "listener three password".toCharArray(),
            AccessTier.USER);
        WebAccessSessionManager.Configuration configuration = new WebAccessSessionManager.Configuration(4, 32);

        try(WebAccessSessionManager manager = new WebAccessSessionManager(configuration))
        {
            WebAccessSession firstPrimary = manager.create(primary).orElseThrow();
            WebAccessSession secondPrimary = manager.create(primary).orElseThrow();
            WebAccessSession thirdPrimary = manager.create(primary).orElseThrow();
            WebAccessSession fourthPrimary = manager.create(primary).orElseThrow();

            WebAccessSession firstOrdinary = manager.create(firstUser).orElseThrow();
            assertTrue(manager.resolve(firstPrimary.sessionId(), accessService).isEmpty(),
                "the least-recently-used surplus primary session should yield the first ordinary slot");

            WebAccessSession secondOrdinary = manager.create(secondUser).orElseThrow();
            assertTrue(manager.resolve(secondPrimary.sessionId(), accessService).isEmpty(),
                "the remaining surplus primary session should yield the second ordinary slot");

            WebAccessSession thirdOrdinary = manager.create(thirdUser).orElseThrow();
            assertTrue(manager.resolve(firstOrdinary.sessionId(), accessService).isEmpty(),
                "ordinary sessions must rotate after the two primary-reserved sessions remain");
            assertTrue(manager.resolve(secondOrdinary.sessionId(), accessService).isPresent());
            assertTrue(manager.resolve(thirdOrdinary.sessionId(), accessService).isPresent());
            assertTrue(manager.resolve(thirdPrimary.sessionId(), accessService).isPresent());
            assertTrue(manager.resolve(fourthPrimary.sessionId(), accessService).isPresent());
            assertEquals(4, manager.getActiveSessionCount());
        }
    }
}
