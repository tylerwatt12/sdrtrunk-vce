/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.web.http.WebAccessHttpController;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Test
    void httpReloginReusesItsAuthenticatedSessionAtPerAccountCapacity() throws Exception
    {
        Path database = mTemporaryFolder.resolve("capacity-relogin.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        char[] password = "primary admin password".toCharArray();
        accessService.provisionOrResetPrimaryAdmin(password);
        WebAccessSessionManager sessionManager = new WebAccessSessionManager();
        WebAuthenticationService authenticationService = new WebAuthenticationService(accessService,
            sessionManager, LoginThrottle.Configuration.defaults(),
            new AccountLoginAdmissionLimiter.Configuration(16, Duration.ofMinutes(1)), Clock.systemUTC(), 2);
        WebAccessHttpController controller = new WebAccessHttpController(accessService, authenticationService);
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        controller.register(server);
        server.start();

        try
        {
            List<WebAccessSession> sessions = new ArrayList<>();

            for(int session = 0; session < 8; session++)
            {
                WebAuthenticationService.LoginResult result = authenticationService
                    .login("admin", password, "source-" + session).get(10, TimeUnit.SECONDS);
                assertEquals(WebAuthenticationService.LoginStatus.SUCCESS, result.status());
                sessions.add(result.session().orElseThrow());
            }

            WebAccessSession current = sessions.getFirst();
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String body = "{\"username\":\"admin\",\"password\":\"primary admin password\"}";
            HttpResponse<String> ninth = client.send(HttpRequest.newBuilder(origin.resolve(
                    WebAccessHttpController.LOGIN_PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Origin", origin.toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, ninth.statusCode(), "a cookie-less ninth session must be rejected");

            String cookie = WebAccessHttpController.SESSION_COOKIE_NAME + "=" + current.sessionId();
            HttpResponse<String> relogin = client.send(HttpRequest.newBuilder(origin.resolve(
                    WebAccessHttpController.LOGIN_PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Origin", origin.toString())
                .header("Content-Type", "application/json")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, relogin.statusCode(), relogin.body());
            assertTrue(relogin.headers().firstValue("Set-Cookie").orElseThrow().startsWith(cookie + ";"));
            assertTrue(relogin.body().contains(current.csrfToken()));
            assertEquals(8, authenticationService.getActiveSessionCount());

            CompletableFuture<WebAuthenticationService.LoginResult> blocker = authenticationService
                .login("admin", "wrong password".toCharArray(), "cancel-blocker");
            CompletableFuture<WebAuthenticationService.LoginResult> abandoned = authenticationService
                .login("admin", password, "cancelled-relogin", current.sessionId());
            assertTrue(abandoned.cancel(true), "the queued capacity-reuse login should be cancellable");
            assertEquals(WebAuthenticationService.LoginStatus.DENIED,
                blocker.get(10, TimeUnit.SECONDS).status());
            WebAuthenticationService.LoginResult drained = authenticationService
                .login("admin", password, "cancel-drain").get(10, TimeUnit.SECONDS);
            assertEquals(WebAuthenticationService.LoginStatus.SESSION_CAPACITY, drained.status());
            assertEquals(8, authenticationService.getActiveSessionCount());
            assertTrue(authenticationService.resolveSession(current.sessionId()).isPresent(),
                "a canceled caller must not revoke the capacity-reused current session");
        }
        finally
        {
            server.stop(0);
            controller.close();
            executor.shutdownNow();
        }
    }
}
