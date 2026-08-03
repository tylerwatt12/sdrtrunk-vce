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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebAuthenticationServiceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void boundsPasswordWorkAndCreatesRevocableSessions() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        char[] password = "primary admin password".toCharArray();
        accessService.provisionOrResetPrimaryAdmin(password);
        WebAuthenticationService authenticationService = new WebAuthenticationService(accessService);

        WebAuthenticationService.LoginResult denied = authenticationService
            .login("admin", "incorrect password".toCharArray(), "127.0.0.1")
            .get(10, TimeUnit.SECONDS);
        assertEquals(WebAuthenticationService.LoginStatus.DENIED, denied.status());

        WebAuthenticationService.LoginResult accepted = authenticationService
            .login("ADMIN", password, "127.0.0.1").get(10, TimeUnit.SECONDS);
        assertEquals(WebAuthenticationService.LoginStatus.SUCCESS, accepted.status());
        WebAccessSession session = accepted.session().orElseThrow();
        assertTrue(authenticationService.resolveSession(session.sessionId()).isPresent());
        assertTrue(authenticationService.validateCsrf(session.sessionId(), session.csrfToken()));
        assertFalse(accepted.toString().contains(session.sessionId()));
        assertFalse(accepted.toString().contains(session.csrfToken()));

        accessService.provisionOrResetPrimaryAdmin("replacement admin password".toCharArray());
        assertTrue(authenticationService.resolveSession(session.sessionId()).isEmpty());
        authenticationService.close();
        assertEquals(WebAuthenticationService.LoginStatus.BUSY,
            authenticationService.login("admin", password, "127.0.0.1").join().status());
    }
}
