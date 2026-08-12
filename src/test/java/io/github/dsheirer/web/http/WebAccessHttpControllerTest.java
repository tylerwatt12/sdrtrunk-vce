/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAccessSession;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.tls.TlsMaterial;
import io.github.dsheirer.web.tls.TlsMaterialService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebAccessHttpControllerTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ADMIN_PASSWORD = "admin-password-2026";
    private static final String USER_PASSWORD = "listener-password-2026";

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void enforcesSessionsCsrfRolesUserLifecycleAndCapabilityChanges() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseSchema.create(connection);
        }

        WebAccessService accessService = new WebAccessService(database);
        char[] primaryPassword = ADMIN_PASSWORD.toCharArray();

        try
        {
            accessService.provisionOrResetPrimaryAdmin(primaryPassword);
        }
        finally
        {
            Arrays.fill(primaryPassword, '\u0000');
        }

        accessService.setCapabilityTier(WebCapability.DASHBOARD_VIEW, AccessTier.USER);
        WebAccessHttpController controller = new WebAccessHttpController(accessService);
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        controller.register(server);
        server.createContext("/protected", controller.protect(WebCapability.DASHBOARD_VIEW, exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);

            try(OutputStream outputStream = exchange.getResponseBody())
            {
                outputStream.write(body);
            }
        }));
        server.createContext("/public-protected", controller.protect(WebCapability.CREDITS_VIEW, exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);

            try(OutputStream outputStream = exchange.getResponseBody())
            {
                outputStream.write(body);
            }
        }));
        server.createContext("/admin-api", controller.protectApi(WebCapability.ADMIN_ALIASES, exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);

            try(OutputStream outputStream = exchange.getResponseBody())
            {
                outputStream.write(body);
            }
        }));
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> anonymousSession = send(client, request(origin, "/api/v1/auth/session").GET());
            JsonNode anonymous = data(anonymousSession);
            assertEquals(200, anonymousSession.statusCode());
            assertTrue(anonymous.get("configured").booleanValue());
            assertFalse(anonymous.get("authenticated").booleanValue());
            assertEquals("public", anonymous.get("tier").textValue());
            assertFalse(anonymous.at("/capabilities/dashboard").booleanValue());
            assertTrue(anonymous.at("/capabilities/site-access").booleanValue());
            assertFalse(anonymous.at("/capabilities/admin-users").booleanValue());
            assertFalse(anonymous.at("/capabilities/admin-aliases").booleanValue());
            assertEquals(200, send(client, request(origin, "/public-protected").GET()).statusCode());

            HttpResponse<String> staleSession = send(client, request(origin, "/api/v1/auth/session")
                .header("Cookie", WebAccessHttpController.SESSION_COOKIE_NAME + "=expired-session")
                .GET());
            assertEquals(200, staleSession.statusCode());
            assertFalse(data(staleSession).get("authenticated").booleanValue());
            assertTrue(staleSession.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"));

            HttpResponse<String> anonymousProtected = send(client, request(origin, "/protected").GET());
            assertEquals(401, anonymousProtected.statusCode());
            assertEquals("authentication_required", json(anonymousProtected).at("/error/code").textValue());
            assertEquals(401, json(anonymousProtected).at("/error/status").intValue());
            assertTrue(anonymousProtected.headers().firstValue("Content-Security-Policy").isPresent());
            assertEquals(401, send(client, request(origin, "/admin-api")
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode());

            Login admin = login(client, origin, "admin", ADMIN_PASSWORD);
            assertEquals("admin", admin.body().get("tier").textValue());
            assertTrue(admin.cookieHeader().contains(WebAccessHttpController.SESSION_COOKIE_NAME + "="));
            assertTrue(admin.setCookie().contains("HttpOnly"));
            assertTrue(admin.setCookie().contains("SameSite=Strict"));
            assertFalse(admin.setCookie().contains("Secure"));
            assertTrue(admin.body().at("/capabilities/admin-aliases").booleanValue());

            assertEquals(200, send(client, request(origin, "/protected")
                .header("Cookie", admin.cookieHeader()).GET()).statusCode());
            assertEquals(200, send(client, request(origin, "/admin-api")
                .header("Cookie", admin.cookieHeader()).GET()).statusCode());
            HttpResponse<String> usersResponse = send(client, request(origin, "/api/v1/admin/users")
                .header("Cookie", admin.cookieHeader()).GET());
            assertEquals(200, usersResponse.statusCode());
            assertEquals(WebAccessService.MAXIMUM_USERS, data(usersResponse).get("maximum_users").intValue());
            assertTrue(data(usersResponse).get("users").isArray());

            String createBody = OBJECT_MAPPER.writeValueAsString(Map.of(
                "username", "listener", "password", USER_PASSWORD, "tier", "user"));
            HttpResponse<String> missingCsrf = send(client, request(origin, "/api/v1/admin/users")
                .header("Origin", origin.toString())
                .header("Cookie", admin.cookieHeader())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody)));
            assertEquals(403, missingCsrf.statusCode());
            assertEquals("request_rejected", json(missingCsrf).at("/error/code").textValue());
            assertEquals(403, send(client, request(origin, "/admin-api")
                .header("Origin", origin.toString())
                .header("Cookie", admin.cookieHeader())
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
            assertEquals(403, send(client, request(origin, "/admin-api")
                .header("Origin", "http://example.invalid")
                .header("Cookie", admin.cookieHeader())
                .header(WebAccessHttpController.CSRF_HEADER_NAME, admin.csrfToken())
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
            assertEquals(200, send(client, mutation(origin, "/admin-api", admin)
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode());

            HttpResponse<String> created = send(client, mutation(origin, "/api/v1/admin/users", admin)
                .POST(HttpRequest.BodyPublishers.ofString(createBody)));
            assertEquals(201, created.statusCode());
            assertEquals("listener", data(created).get("username").textValue());
            assertTrue(data(created).has("password_changed_at_epoch_millis"));
            assertFalse(data(created).has("passwordChangedAtEpochMillis"));

            String keepUserTier = OBJECT_MAPPER.writeValueAsString(Map.of("tier", "user"));
            assertEquals(200, send(client, mutation(origin, "/api/v1/admin/users/%6cistener", admin)
                .PUT(HttpRequest.BodyPublishers.ofString(keepUserTier))).statusCode());
            for(String rejectedPath: java.util.List.of(
                "/api/v1/admin/users/%2561dmin",
                "/api/v1/admin/users/listener+",
                "/api/v1/admin/users/listener%2Fextra",
                "/api/v1/admin/users/%C3%28"))
            {
                assertEquals(404, send(client, mutation(origin, rejectedPath, admin)
                    .PUT(HttpRequest.BodyPublishers.ofString(keepUserTier))).statusCode(), rejectedPath);
            }

            Login listener = login(client, origin, "listener", USER_PASSWORD);
            assertEquals("user", listener.body().get("tier").textValue());
            assertEquals(200, send(client, request(origin, "/protected")
                .header("Cookie", listener.cookieHeader()).GET()).statusCode());
            assertEquals(403, send(client, mutation(origin, "/admin-api", listener)
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode());

            String requireSiteLogin = OBJECT_MAPPER.writeValueAsString(
                Map.of("capability", "site-access", "tier", "user"));
            HttpResponse<String> siteLocked = send(client, mutation(origin, "/api/v1/admin/access", admin)
                .PUT(HttpRequest.BodyPublishers.ofString(requireSiteLogin)));
            assertEquals(200, siteLocked.statusCode());
            assertEquals("user", data(siteLocked).get("required_tier").textValue());
            assertEquals(401, send(client, request(origin, "/protected").GET()).statusCode());
            assertEquals(401, send(client, request(origin, "/public-protected").GET()).statusCode());
            assertEquals(200, send(client, request(origin, "/protected")
                .header("Cookie", listener.cookieHeader()).GET()).statusCode());
            assertEquals(200, send(client, request(origin, "/public-protected")
                .header("Cookie", listener.cookieHeader()).GET()).statusCode());
            JsonNode lockedAnonymous = data(send(client, request(origin, "/api/v1/auth/session").GET()));
            assertFalse(lockedAnonymous.at("/capabilities/site-access").booleanValue());
            assertFalse(lockedAnonymous.at("/capabilities/credits").booleanValue());

            String uppercaseTier = OBJECT_MAPPER.writeValueAsString(
                Map.of("capability", "dashboard", "tier", "ADMIN"));
            assertEquals(400, send(client, mutation(origin, "/api/v1/admin/access", admin)
                .PUT(HttpRequest.BodyPublishers.ofString(uppercaseTier))).statusCode());

            String adminOnly = OBJECT_MAPPER.writeValueAsString(
                Map.of("capability", "dashboard", "tier", "admin"));
            HttpResponse<String> policyChanged = send(client, mutation(origin, "/api/v1/admin/access", admin)
                .PUT(HttpRequest.BodyPublishers.ofString(adminOnly)));
            assertEquals(200, policyChanged.statusCode());
            assertEquals("admin", data(policyChanged).get("required_tier").textValue());
            assertEquals(403, send(client, request(origin, "/protected")
                .header("Cookie", listener.cookieHeader()).GET()).statusCode());

            String promote = OBJECT_MAPPER.writeValueAsString(Map.of("tier", "admin"));
            HttpResponse<String> promoted = send(client,
                mutation(origin, "/api/v1/admin/users/listener", admin)
                    .PUT(HttpRequest.BodyPublishers.ofString(promote)));
            assertEquals(200, promoted.statusCode());
            assertEquals("admin", data(promoted).get("tier").textValue());
            assertEquals(401, send(client, request(origin, "/protected")
                .header("Cookie", listener.cookieHeader()).GET()).statusCode());

            Login promotedLogin = login(client, origin, "listener", USER_PASSWORD);
            assertEquals("admin", promotedLogin.body().get("tier").textValue());
            assertEquals(200, send(client, request(origin, "/protected")
                .header("Cookie", promotedLogin.cookieHeader()).GET()).statusCode());

            HttpResponse<String> primaryMutation = send(client,
                mutation(origin, "/api/v1/admin/users/admin", admin)
                    .PUT(HttpRequest.BodyPublishers.ofString(promote)));
            assertEquals(400, primaryMutation.statusCode());

            HttpResponse<String> deleted = send(client,
                mutation(origin, "/api/v1/admin/users/listener", admin).DELETE());
            assertEquals(200, deleted.statusCode());
            assertEquals(401, send(client, request(origin, "/protected")
                .header("Cookie", promotedLogin.cookieHeader()).GET()).statusCode());

            HttpResponse<String> logout = send(client, mutation(origin, "/api/v1/auth/logout", admin)
                .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(200, logout.statusCode());
            assertTrue(logout.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"));
        }
        finally
        {
            server.stop(0);
            controller.close();
            executor.shutdownNow();
        }
    }

    @Test
    void failedLoginResponseDiscardsNewSessionAndPreservesExistingSession() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("failed-login-response.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseSchema.create(connection);
        }

        WebAccessService accessService = new WebAccessService(database);
        char[] provisionPassword = ADMIN_PASSWORD.toCharArray();

        try
        {
            accessService.provisionOrResetPrimaryAdmin(provisionPassword);
        }
        finally
        {
            Arrays.fill(provisionPassword, '\u0000');
        }

        WebAuthenticationService authenticationService = new WebAuthenticationService(accessService);
        WebAccessHttpController controller = new WebAccessHttpController(accessService, authenticationService);
        char[] firstPassword = ADMIN_PASSWORD.toCharArray();
        char[] secondPassword = ADMIN_PASSWORD.toCharArray();

        try
        {
            WebAccessSession existing = authenticationService.login("admin", firstPassword, "first")
                .get(30, java.util.concurrent.TimeUnit.SECONDS).session().orElseThrow();
            WebAccessSession created = authenticationService.login("admin", secondPassword, "second")
                .get(30, java.util.concurrent.TimeUnit.SECONDS).session().orElseThrow();
            assertEquals(2, authenticationService.getActiveSessionCount());

            assertThrows(IOException.class,
                () -> controller.deliverLoginResponse(failingResponseExchange(), created, existing.sessionId()));

            assertEquals(1, authenticationService.getActiveSessionCount());
            assertTrue(authenticationService.resolveSession(existing.sessionId()).isPresent());
            assertTrue(authenticationService.resolveSession(created.sessionId()).isEmpty());

            assertThrows(IOException.class,
                () -> controller.deliverLoginResponse(failingResponseExchange(), existing, existing.sessionId()));
            assertEquals(1, authenticationService.getActiveSessionCount(),
                "a failed response must not revoke a capacity-reused session");
            assertTrue(authenticationService.resolveSession(existing.sessionId()).isPresent());
        }
        finally
        {
            Arrays.fill(firstPassword, '\u0000');
            Arrays.fill(secondPassword, '\u0000');
            controller.close();
        }
    }

    @Test
    void marksTheSessionCookieSecureOnHttps() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("https-sdrtrunk.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseSchema.create(connection);
        }

        WebAccessService accessService = new WebAccessService(database);
        char[] password = ADMIN_PASSWORD.toCharArray();

        try
        {
            accessService.provisionOrResetPrimaryAdmin(password);
        }
        finally
        {
            Arrays.fill(password, '\u0000');
        }

        TlsMaterial material = new TlsMaterialService(mTemporaryDirectory.resolve("tls-root"))
            .generateSelfSigned("localhost", java.util.List.of("localhost", "127.0.0.1"));
        WebAccessHttpController controller = new WebAccessHttpController(accessService);
        HttpsServer server = HttpsServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(material.createServerSslContext()));
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        controller.register(server);
        server.start();

        try
        {
            URI origin = URI.create("https://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder()
                .sslContext(trustAllSslContext())
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            Login login = login(client, origin, "admin", ADMIN_PASSWORD);
            assertTrue(login.setCookie().contains("; Secure"));
        }
        finally
        {
            server.stop(0);
            controller.close();
            executor.shutdownNow();
        }
    }

    private static Login login(HttpClient client, URI origin, String username, String password) throws Exception
    {
        String body = OBJECT_MAPPER.writeValueAsString(Map.of("username", username, "password", password));
        HttpResponse<String> response = send(client, request(origin, "/api/v1/auth/login")
            .header("Origin", origin.toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode json = data(response);
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return new Login(json, setCookie.substring(0, setCookie.indexOf(';')), setCookie,
            json.get("csrf_token").textValue());
    }

    private static HttpExchange failingResponseExchange()
    {
        return new HttpExchange()
        {
            private final Headers mRequestHeaders = new Headers();
            private final Headers mResponseHeaders = new Headers();
            private final Map<String,Object> mAttributes = new HashMap<>();
            private int mResponseCode = -1;

            @Override
            public Headers getRequestHeaders()
            {
                return mRequestHeaders;
            }

            @Override
            public Headers getResponseHeaders()
            {
                return mResponseHeaders;
            }

            @Override
            public URI getRequestURI()
            {
                return URI.create(WebAccessHttpController.LOGIN_PATH);
            }

            @Override
            public String getRequestMethod()
            {
                return "POST";
            }

            @Override
            public HttpContext getHttpContext()
            {
                return null;
            }

            @Override
            public void close()
            {
            }

            @Override
            public InputStream getRequestBody()
            {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public OutputStream getResponseBody()
            {
                return new OutputStream()
                {
                    @Override
                    public void write(int value) throws IOException
                    {
                        throw new IOException("simulated browser disconnect");
                    }
                };
            }

            @Override
            public void sendResponseHeaders(int responseCode, long responseLength)
            {
                mResponseCode = responseCode;
            }

            @Override
            public InetSocketAddress getRemoteAddress()
            {
                return new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
            }

            @Override
            public int getResponseCode()
            {
                return mResponseCode;
            }

            @Override
            public InetSocketAddress getLocalAddress()
            {
                return new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080);
            }

            @Override
            public String getProtocol()
            {
                return "HTTP/1.1";
            }

            @Override
            public Object getAttribute(String name)
            {
                return mAttributes.get(name);
            }

            @Override
            public void setAttribute(String name, Object value)
            {
                mAttributes.put(name, value);
            }

            @Override
            public void setStreams(InputStream input, OutputStream output)
            {
            }

            @Override
            public HttpPrincipal getPrincipal()
            {
                return null;
            }
        };
    }

    private static HttpRequest.Builder mutation(URI origin, String path, Login login)
    {
        return request(origin, path)
            .header("Origin", origin.toString())
            .header("Cookie", login.cookieHeader())
            .header(WebAccessHttpController.CSRF_HEADER_NAME, login.csrfToken())
            .header("Content-Type", "application/json");
    }

    private static HttpRequest.Builder request(URI origin, String path)
    {
        return HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(30));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder builder) throws Exception
    {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception
    {
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception
    {
        return json(response).get("data");
    }

    private static SSLContext trustAllSslContext() throws Exception
    {
        X509TrustManager trustManager = new X509TrustManager()
        {
            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers()
            {
                return new java.security.cert.X509Certificate[0];
            }

            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authenticationType)
            {
            }

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authenticationType)
            {
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustManager}, new SecureRandom());
        return context;
    }

    private record Login(JsonNode body, String cookieHeader, String setCookie, String csrfToken)
    {
    }
}
