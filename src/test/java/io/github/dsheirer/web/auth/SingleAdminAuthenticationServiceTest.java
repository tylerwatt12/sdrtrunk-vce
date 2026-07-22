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
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleAdminAuthenticationServiceTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void provisionsOneAccountAuthenticatesAndInvalidatesOnReset() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAdminCredentialStore store = new WebAdminCredentialStore(database);
        Clock clock = Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC);
        Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher(WebAdminCredential.MINIMUM_ITERATIONS,
            new SecureRandom(), clock);
        WebAdminSessionManager sessions = new WebAdminSessionManager(
            new WebAdminSessionManager.Configuration(2, Duration.ofMinutes(5), Duration.ofHours(1), 32),
            new SecureRandom(), clock);

        try(SingleAdminAuthenticationService service = new SingleAdminAuthenticationService(store, hasher, sessions,
            new LoginThrottle.Configuration(8, 3, Duration.ofMinutes(1), Duration.ofMinutes(1)), clock, 2))
        {
            assertFalse(service.isConfigured());
            service.provisionOrReset("Admin", "first secure administrator password".toCharArray());
            assertTrue(service.isConfigured());
            SingleAdminAuthenticationService.LoginResult denied = service.login("admin",
                "not the password".toCharArray(), "127.0.0.1").get(5, TimeUnit.SECONDS);
            assertEquals(SingleAdminAuthenticationService.LoginStatus.DENIED, denied.status());
            SingleAdminAuthenticationService.LoginResult accepted = service.login("ADMIN",
                "first secure administrator password".toCharArray(), "127.0.0.1").get(5, TimeUnit.SECONDS);
            assertEquals(SingleAdminAuthenticationService.LoginStatus.SUCCESS, accepted.status());
            WebAdminSession session = accepted.session().orElseThrow();
            assertTrue(service.resolveSession(session.sessionId()).isPresent());
            assertTrue(service.validateCsrf(session.sessionId(), session.csrfToken()));
            assertFalse(accepted.toString().contains(session.sessionId()));

            service.provisionOrReset("admin", "replacement administrator password".toCharArray());
            assertTrue(service.resolveSession(session.sessionId()).isEmpty());
            assertEquals(2, service.getCredentialMetadata().orElseThrow().authGeneration());
            assertEquals(2, store.load().orElseThrow().authGeneration());
        }

        try(SingleAdminAuthenticationService restarted = new SingleAdminAuthenticationService(store, hasher,
            new WebAdminSessionManager(), LoginThrottle.Configuration.defaults(), clock, 2))
        {
            assertEquals(0, restarted.getActiveSessionCount(), "sessions must not survive application restart");
            assertEquals(SingleAdminAuthenticationService.LoginStatus.SUCCESS,
                restarted.login("admin", "replacement administrator password".toCharArray(), "127.0.0.1")
                    .get(5, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void accountWideAdmissionStopsAManySourceBurstBeforeMorePasswordWork() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("admission.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Clock clock = Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC);

        try(SingleAdminAuthenticationService service = new SingleAdminAuthenticationService(
            new WebAdminCredentialStore(database),
            new Pbkdf2PasswordHasher(WebAdminCredential.MINIMUM_ITERATIONS, new SecureRandom(), clock),
            new WebAdminSessionManager(), LoginThrottle.Configuration.defaults(),
            new AccountLoginAdmissionLimiter.Configuration(1, Duration.ofMinutes(1)), clock, 1))
        {
            service.provisionOrReset("admin", "secure administrator password".toCharArray());
            assertEquals(SingleAdminAuthenticationService.LoginStatus.DENIED,
                service.login("admin", "wrong administrator password".toCharArray(), "192.0.2.1")
                    .get(5, TimeUnit.SECONDS).status());

            SingleAdminAuthenticationService.LoginResult secondSource = service.login("admin",
                "secure administrator password".toCharArray(), "192.0.2.2").get(5, TimeUnit.SECONDS);
            assertEquals(SingleAdminAuthenticationService.LoginStatus.THROTTLED, secondSource.status());
            assertEquals(Duration.ofMinutes(1).toMillis(), secondSource.retryAfterMillis());
            assertEquals(0, service.getActiveSessionCount(),
                "globally denied work must not reach password verification or create a session");
        }
    }
}
