/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.web.WebApplicationService;
import io.github.dsheirer.web.WebResponses;
import io.github.dsheirer.web.access.AuthorizationSubject;
import io.github.dsheirer.web.access.RemoteAddressAdmissionPolicy;
import io.github.dsheirer.web.access.WebRequestSubjectResolver.WebAuthorization;
import io.github.dsheirer.web.auth.WebAdminAuthenticationHandler.MutationAuthorization;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebAdminAuthenticationHandlerTest
{
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void servesStrictLoginSessionLogoutAndSharesCookieWithBothResolvers() throws Exception
    {
        try(AuthRig rig = new AuthRig(mTemporaryDirectory.resolve("auth.sqlite")))
        {
            HttpResponse<String> initial = rig.get(WebAdminAuthenticationHandler.SESSION_PATH, null, null);
            assertEquals(200, initial.statusCode());
            assertTrue(json(initial).get("configured").booleanValue());
            assertEquals(false, json(initial).get("authenticated").booleanValue());
            assertEquals("no-store, max-age=0", initial.headers().firstValue("cache-control").orElseThrow());
            assertEquals("nosniff", initial.headers().firstValue("x-content-type-options").orElseThrow());
            assertEquals("DENY", initial.headers().firstValue("x-frame-options").orElseThrow());

            HttpResponse<String> chunkedSessionBody = rig.getUnknownLength(
                WebAdminAuthenticationHandler.SESSION_PATH, "unexpected");
            assertEquals(400, chunkedSessionBody.statusCode(),
                "session status must reject an unknown-length body without reading it");

            HttpResponse<String> wrongOrigin = rig.post(WebAdminAuthenticationHandler.LOGIN_PATH,
                "https://attacker.invalid", "application/json", loginJson(AuthRig.PASSWORD), null, null);
            assertEquals(403, wrongOrigin.statusCode());
            assertEquals("request_rejected", json(wrongOrigin).get("error").textValue());

            HttpResponse<String> wrongMedia = rig.post(WebAdminAuthenticationHandler.LOGIN_PATH, rig.origin(),
                "text/plain", loginJson(AuthRig.PASSWORD), null, null);
            assertEquals(415, wrongMedia.statusCode());

            HttpResponse<String> wrongCharset = rig.post(WebAdminAuthenticationHandler.LOGIN_PATH, rig.origin(),
                "application/json; charset=iso-8859-1", loginJson(AuthRig.PASSWORD), null, null);
            assertEquals(415, wrongCharset.statusCode());

            HttpResponse<String> unknownField = rig.post(WebAdminAuthenticationHandler.LOGIN_PATH, rig.origin(),
                "application/json", "{\"username\":\"admin\",\"password\":\"" + AuthRig.PASSWORD +
                    "\",\"remember\":true}", null, null);
            assertEquals(400, unknownField.statusCode());

            HttpResponse<String> duplicate = rig.post(WebAdminAuthenticationHandler.LOGIN_PATH, rig.origin(),
                "application/json", "{\"username\":\"admin\",\"password\":\"" + AuthRig.PASSWORD +
                    "\",\"password\":\"" + AuthRig.PASSWORD + "\"}", null, null);
            assertEquals(400, duplicate.statusCode());

            HttpResponse<String> trailing = rig.post(WebAdminAuthenticationHandler.LOGIN_PATH, rig.origin(),
                "application/json", loginJson(AuthRig.PASSWORD) + "{}", null, null);
            assertEquals(400, trailing.statusCode());

            HttpResponse<String> oversized = rig.post(WebAdminAuthenticationHandler.LOGIN_PATH, rig.origin(),
                "application/json", "x".repeat(1_025), null, null);
            assertEquals(413, oversized.statusCode());

            HttpResponse<String> chunkedOversized = rig.postUnknownLength(
                WebAdminAuthenticationHandler.LOGIN_PATH, rig.origin(), "application/json", "x".repeat(1_025));
            assertEquals(413, chunkedOversized.statusCode());

            HttpResponse<String> denied = rig.post(WebAdminAuthenticationHandler.LOGIN_PATH, rig.origin(),
                "application/json", loginJson("not-the-administrator-password"), null, null);
            assertEquals(401, denied.statusCode());
            assertEquals("invalid_credentials", json(denied).get("error").textValue());
            assertFalse(denied.body().contains("admin"), "failure must not identify the configured account");

            HttpResponse<String> accepted = rig.post(WebAdminAuthenticationHandler.LOGIN_PATH, rig.origin(),
                "application/json", loginJson(AuthRig.PASSWORD), null, null);
            assertEquals(200, accepted.statusCode());
            JsonNode acceptedJson = json(accepted);
            assertTrue(acceptedJson.get("authenticated").booleanValue());
            String csrf = acceptedJson.get("csrfToken").textValue();
            String setCookie = accepted.headers().firstValue("set-cookie").orElseThrow();
            assertTrue(setCookie.contains("HttpOnly"));
            assertTrue(setCookie.contains("SameSite=Strict"));
            assertTrue(setCookie.contains("Path=/"));
            assertFalse(setCookie.contains("Secure"), "loopback HTTP cookie must not claim HTTPS transport");
            String cookie = setCookie.substring(0, setCookie.indexOf(';'));

            HttpResponse<String> current = rig.get(WebAdminAuthenticationHandler.SESSION_PATH, cookie, null);
            assertEquals(200, current.statusCode());
            assertTrue(json(current).get("authenticated").booleanValue());
            assertEquals(csrf, json(current).get("csrfToken").textValue());

            HttpResponse<String> httpSubject = rig.get(AuthRig.SUBJECT_PATH, cookie, null);
            assertEquals("AUTHENTICATED_ADMIN", httpSubject.body());
            WebAuthorization webAuthorization = rig.captureWebAuthorization(cookie);
            assertEquals(AuthorizationSubject.AUTHENTICATED_ADMIN, webAuthorization.subject());
            assertTrue(webAuthorization.isSessionValid());
            assertEquals(AuthorizationSubject.AUTHENTICATED_ADMIN, rig.resolveWebSocket(cookie));

            MutationAuthorization mutationAuthorization = rig.captureMutationAuthorization(cookie, csrf,
                rig.origin(), false);
            assertTrue(mutationAuthorization.authorized());
            assertTrue(mutationAuthorization.isSessionValid());
            assertFalse(rig.captureMutationAuthorization(cookie, csrf, null, false).authorized(),
                "state-changing requests require an Origin header");
            assertFalse(rig.captureMutationAuthorization(cookie, csrf, "https://attacker.invalid", false)
                .authorized());
            assertFalse(rig.captureMutationAuthorization(cookie + "; " + cookie, csrf, rig.origin(), false)
                .authorized(), "duplicate administrator cookies must be rejected");
            assertFalse(rig.captureMutationAuthorization(cookie, null, rig.origin(), false).authorized());
            assertFalse(rig.captureMutationAuthorization(cookie, csrf, rig.origin(), true).authorized(),
                "duplicate CSRF headers must be rejected");
            assertFalse(rig.captureMutationAuthorization(cookie, "wrong-token", rig.origin(), false).authorized());

            HttpResponse<String> missingCsrf = rig.post(WebAdminAuthenticationHandler.LOGOUT_PATH, rig.origin(),
                null, "", cookie, null);
            assertEquals(403, missingCsrf.statusCode());
            assertEquals("AUTHENTICATED_ADMIN", rig.get(AuthRig.SUBJECT_PATH, cookie, null).body());

            HttpResponse<String> loggedOut = rig.post(WebAdminAuthenticationHandler.LOGOUT_PATH, rig.origin(),
                null, "", cookie, csrf);
            assertEquals(200, loggedOut.statusCode());
            assertFalse(json(loggedOut).get("authenticated").booleanValue());
            String expiredCookie = loggedOut.headers().firstValue("set-cookie").orElseThrow();
            assertTrue(expiredCookie.contains("Expires=Thu, 01 Jan 1970"), expiredCookie);
            assertEquals("ANONYMOUS", rig.get(AuthRig.SUBJECT_PATH, cookie, null).body());
            assertFalse(webAuthorization.isSessionValid(), "logout must revoke an already-open web authorization");
            assertFalse(mutationAuthorization.isSessionValid(),
                "logout must revoke an already-authorized asynchronous mutation");
            assertFalse(rig.captureMutationAuthorization(cookie, csrf, rig.origin(), false).authorized(),
                "a stale session cookie and CSRF token must not authorize a new mutation");

            HttpResponse<String> noResetRoute = rig.post("/api/v1/auth/reset", rig.origin(), "application/json",
                "{}", null, null);
            assertEquals(404, noResetRoute.statusCode(), "credential bootstrap must not exist as an HTTP route");
        }
    }

    @Test
    void inadmissiblePeerCannotReachAnyAuthenticationOperation() throws Exception
    {
        AtomicInteger operationCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        AtomicInteger policyCalls = new AtomicInteger();
        AtomicReference<WebAdminAuthenticationHandler> handlerReference = new AtomicReference<>();
        WebAdminAuthenticationOperations operations = new WebAdminAuthenticationOperations()
        {
            private void touched()
            {
                operationCalls.incrementAndGet();
            }

            @Override
            public boolean isConfigured()
            {
                touched();
                return true;
            }

            @Override
            public Optional<SingleAdminAuthenticationService.CredentialMetadata> getCredentialMetadata()
            {
                touched();
                return Optional.empty();
            }

            @Override
            public CompletableFuture<SingleAdminAuthenticationService.LoginResult> login(String username,
                                                                                           char[] password,
                                                                                           String sourceKey)
            {
                touched();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public Optional<WebAdminSession> resolveSession(String sessionId)
            {
                touched();
                return Optional.empty();
            }

            @Override
            public boolean validateCsrf(String sessionId, String csrfToken)
            {
                touched();
                return false;
            }

            @Override
            public boolean logout(String sessionId)
            {
                touched();
                return false;
            }
        };
        Handler fallback = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                fallbackCalls.incrementAndGet();

                if("/mutation-authorization".equals(Request.getPathInContext(request)))
                {
                    MutationAuthorization authorization = handlerReference.get().authorizeMutation(request);
                    WebResponses.text(response, callback, 200, "text/plain; charset=utf-8",
                        authorization.authorized() ? "authorized" : "rejected");
                    return true;
                }

                WebResponses.text(response, callback, 500, "text/plain; charset=utf-8", "unexpected fallback");
                return true;
            }
        };
        RemoteAddressAdmissionPolicy denyPolicy = request ->
        {
            policyCalls.incrementAndGet();
            return false;
        };
        WebAdminAuthenticationHandler handler = new WebAdminAuthenticationHandler(operations, fallback,
            WebAdminAuthenticationHandler.Configuration.defaults(), denyPolicy);
        handlerReference.set(handler);

        try(WebApplicationService application = new WebApplicationService(
            WebApplicationService.Configuration.ephemeralLoopback(), handler, container -> {});
            HttpClient client = HttpClient.newHttpClient())
        {
            application.start();
            URI baseUri = application.getBaseUri();
            String origin = baseUri.getScheme() + "://" + baseUri.getAuthority();
            HttpResponse<String> session = client.send(HttpRequest.newBuilder(baseUri.resolve(
                    WebAdminAuthenticationHandler.SESSION_PATH)).timeout(TEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> login = client.send(HttpRequest.newBuilder(baseUri.resolve(
                    WebAdminAuthenticationHandler.LOGIN_PATH))
                    .timeout(TEST_TIMEOUT)
                    .header("Origin", origin).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(loginJson(AuthRig.PASSWORD))).build(),
                HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> logout = client.send(HttpRequest.newBuilder(baseUri.resolve(
                    WebAdminAuthenticationHandler.LOGOUT_PATH))
                    .timeout(TEST_TIMEOUT)
                    .header("Origin", origin).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> mutation = client.send(HttpRequest.newBuilder(baseUri.resolve(
                    "/mutation-authorization"))
                    .timeout(TEST_TIMEOUT)
                    .header("Origin", origin)
                    .header("Cookie", WebAdminAuthenticationHandler.SESSION_COOKIE_NAME + "=opaque")
                    .header(WebAdminAuthenticationHandler.CSRF_HEADER_NAME, "opaque")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

            assertEquals(403, session.statusCode());
            assertEquals(403, login.statusCode());
            assertEquals(403, logout.statusCode());
            assertEquals(200, mutation.statusCode());
            assertEquals("rejected", mutation.body());
            assertEquals(4, policyCalls.get());
            assertEquals(0, operationCalls.get(),
                "peer admission must precede session lookup, login hashing, and logout work");
            assertEquals(1, fallbackCalls.get(), "only the test mutation route should reach the fallback");
        }
    }

    @Test
    void buildsTransportAppropriateCookieAndRecognizesOnlyRealLoopbackAddresses() throws Exception
    {
        HttpCookie clearCookie = WebAdminAuthenticationHandler.sessionCookie("session", false);
        assertTrue(clearCookie.isHttpOnly());
        assertEquals(HttpCookie.SameSite.STRICT, clearCookie.getSameSite());
        assertEquals("/", clearCookie.getPath());
        assertFalse(clearCookie.isSecure());

        HttpCookie secureCookie = WebAdminAuthenticationHandler.sessionCookie("session", true);
        assertTrue(secureCookie.isSecure());
        assertTrue(WebAdminAuthenticationHandler.isLoopback(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 1234)));
        assertFalse(WebAdminAuthenticationHandler.isLoopback(
            new InetSocketAddress(InetAddress.getByName("192.0.2.10"), 1234)));
        assertFalse(WebAdminAuthenticationHandler.isLoopback(
            InetSocketAddress.createUnresolved("localhost", 1234)));
    }

    private static String loginJson(String password)
    {
        return "{\"username\":\"admin\",\"password\":\"" + password + "\"}";
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception
    {
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static final class AuthRig implements AutoCloseable
    {
        private static final String PASSWORD = "test administrator password 123!";
        private static final String SUBJECT_PATH = "/subject";
        private static final String WEB_AUTHORIZATION_PATH = "/web-authorization";
        private static final String MUTATION_AUTHORIZATION_PATH = "/mutation-authorization";
        private static final String WEB_SOCKET_PATH = "/ws-auth-subject";
        private final SingleAdminAuthenticationService authenticationService;
        private final WebAdminAuthenticationHandler authenticationHandler;
        private final WebApplicationService application;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(TEST_TIMEOUT).build();
        private final AtomicReference<CompletableFuture<AuthorizationSubject>> nextWebSocketSubject =
            new AtomicReference<>(new CompletableFuture<>());
        private final AtomicReference<CompletableFuture<WebAuthorization>> nextWebAuthorization =
            new AtomicReference<>(new CompletableFuture<>());
        private final AtomicReference<CompletableFuture<MutationAuthorization>> nextMutationAuthorization =
            new AtomicReference<>(new CompletableFuture<>());

        private AuthRig(Path database) throws Exception
        {
            SdrTrunkDatabaseStartup.createGlobalDatabase(database);
            Clock clock = Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC);
            authenticationService = new SingleAdminAuthenticationService(new WebAdminCredentialStore(database),
                new Pbkdf2PasswordHasher(WebAdminCredential.MINIMUM_ITERATIONS, new SecureRandom(), clock),
                new WebAdminSessionManager(new WebAdminSessionManager.Configuration(4, Duration.ofMinutes(5),
                    Duration.ofHours(1), 32), new SecureRandom(), clock),
                new LoginThrottle.Configuration(16, 5, Duration.ofMinutes(1), Duration.ofMinutes(1)), clock, 2);
            authenticationService.provisionOrReset("admin", PASSWORD.toCharArray());

            AtomicReference<WebAdminAuthenticationHandler> handlerReference = new AtomicReference<>();
            Handler fallback = new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback)
                {
                    if(SUBJECT_PATH.equals(Request.getPathInContext(request)))
                    {
                        WebResponses.text(response, callback, 200, "text/plain; charset=utf-8",
                            handlerReference.get().webRequestSubjectResolver().resolve(request).name());
                        return true;
                    }

                    if(WEB_AUTHORIZATION_PATH.equals(Request.getPathInContext(request)))
                    {
                        WebAuthorization authorization = handlerReference.get().webRequestSubjectResolver()
                            .resolveAuthorization(request);
                        nextWebAuthorization.get().complete(authorization);
                        WebResponses.text(response, callback, 200, "text/plain; charset=utf-8",
                            authorization.subject().name());
                        return true;
                    }

                    if(MUTATION_AUTHORIZATION_PATH.equals(Request.getPathInContext(request)))
                    {
                        MutationAuthorization authorization = handlerReference.get().authorizeMutation(request);
                        nextMutationAuthorization.get().complete(authorization);
                        WebResponses.text(response, callback, 200, "text/plain; charset=utf-8",
                            authorization.authorized() ? "authorized" : "rejected");
                        return true;
                    }

                    WebResponses.text(response, callback, 404, "text/plain; charset=utf-8", "not found");
                    return true;
                }
            };
            authenticationHandler = new WebAdminAuthenticationHandler(authenticationService, fallback);
            handlerReference.set(authenticationHandler);
            application = new WebApplicationService(WebApplicationService.Configuration.ephemeralLoopback(),
                authenticationHandler, container -> container.addMapping(WEB_SOCKET_PATH, (request, response,
                                                                                              callback) ->
                {
                    nextWebSocketSubject.get().complete(
                        authenticationHandler.signalSubjectResolver().resolve(request));
                    return new Session.Listener.AutoDemanding() {};
                }));
            application.start();
        }

        private URI baseUri()
        {
            return application.getBaseUri();
        }

        private String origin()
        {
            URI uri = baseUri();
            return uri.getScheme() + "://" + uri.getAuthority();
        }

        private HttpResponse<String> get(String path, String cookie, String origin) throws Exception
        {
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri().resolve(path))
                .timeout(TEST_TIMEOUT).GET();
            optionalHeader(builder, "Cookie", cookie);
            optionalHeader(builder, "Origin", origin);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> post(String path, String origin, String contentType, String body, String cookie,
                                          String csrf) throws Exception
        {
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri().resolve(path)).timeout(TEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body));
            optionalHeader(builder, "Origin", origin);
            optionalHeader(builder, "Content-Type", contentType);
            optionalHeader(builder, "Cookie", cookie);
            optionalHeader(builder, WebAdminAuthenticationHandler.CSRF_HEADER_NAME, csrf);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> postUnknownLength(String path, String origin, String contentType, String body)
            throws Exception
        {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(baseUri().resolve(path)).timeout(TEST_TIMEOUT)
                .header("Origin", origin)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
                .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> getUnknownLength(String path, String body) throws Exception
        {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(baseUri().resolve(path)).timeout(TEST_TIMEOUT)
                .method("GET", HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
                .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        private AuthorizationSubject resolveWebSocket(String cookie) throws Exception
        {
            CompletableFuture<AuthorizationSubject> subject = new CompletableFuture<>();
            nextWebSocketSubject.set(subject);
            URI uri = URI.create("ws://" + baseUri().getAuthority() + WEB_SOCKET_PATH);
            WebSocket socket = client.newWebSocketBuilder().connectTimeout(TEST_TIMEOUT)
                .header("Cookie", cookie)
                .buildAsync(uri, new WebSocket.Listener()
                {
                    @Override
                    public void onOpen(WebSocket webSocket)
                    {
                        webSocket.request(1);
                    }
                }).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            try
            {
                return subject.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
            finally
            {
                socket.abort();
            }
        }

        private WebAuthorization captureWebAuthorization(String cookie) throws Exception
        {
            CompletableFuture<WebAuthorization> authorization = new CompletableFuture<>();
            nextWebAuthorization.set(authorization);
            HttpResponse<String> response = get(WEB_AUTHORIZATION_PATH, cookie, null);
            assertEquals(200, response.statusCode());
            return authorization.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        private MutationAuthorization captureMutationAuthorization(String cookie, String csrf, String origin,
                                                                    boolean duplicateCsrf) throws Exception
        {
            CompletableFuture<MutationAuthorization> authorization = new CompletableFuture<>();
            nextMutationAuthorization.set(authorization);
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri().resolve(MUTATION_AUTHORIZATION_PATH))
                .timeout(TEST_TIMEOUT).POST(HttpRequest.BodyPublishers.noBody());
            optionalHeader(builder, "Origin", origin);
            optionalHeader(builder, "Cookie", cookie);
            optionalHeader(builder, WebAdminAuthenticationHandler.CSRF_HEADER_NAME, csrf);

            if(duplicateCsrf && csrf != null)
            {
                builder.header(WebAdminAuthenticationHandler.CSRF_HEADER_NAME, csrf);
            }

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            return authorization.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        private static void optionalHeader(HttpRequest.Builder builder, String name, String value)
        {
            if(value != null)
            {
                builder.header(name, value);
            }
        }

        @Override
        public void close()
        {
            application.close();
            authenticationService.close();
            client.close();
        }
    }
}
