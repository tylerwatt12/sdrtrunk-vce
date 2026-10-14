/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.support.WebDiagnosticLogService;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.http.WebDiagnosticLogHttpController;
import io.github.dsheirer.web.http.WebRequestSecurity;
import io.github.dsheirer.web.http.WebSessionHttpController;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebDiagnosticLogAccessTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROOT = WebDiagnosticLogHttpController.PATH;
    private static final String ADMIN_PASSWORD = "diagnostic-admin-test-password";
    private static final String USER_PASSWORD = "diagnostic-user-test-password";
    @TempDir Path mDirectory;

    @Test
    void statusEntriesAndDownloadsRequireCurrentAdministratorSessionsAndChangesRequireCsrfAndSameOrigin()
        throws Exception
    {
        Path database = mDirectory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService access = new WebAccessService(database);
        char[] adminPassword = ADMIN_PASSWORD.toCharArray();
        char[] userPassword = USER_PASSWORD.toCharArray();
        try
        {
            access.provisionOrResetPrimaryAdmin(adminPassword);
            access.createUser("listener", userPassword);
        }
        finally
        {
            Arrays.fill(adminPassword, '\u0000');
            Arrays.fill(userPassword, '\u0000');
        }

        Preferences node = Preferences.userRoot().node("/sdrtrunk-vce-tests/web-diagnostic-access-" + UUID.randomUUID());
        Path logs = mDirectory.resolve("logs");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        WebAuthenticationService authentication = new WebAuthenticationService(access);
        WebRequestSecurity security = new WebRequestSecurity(access, authentication);
        // The node is replaced before any preference is read or written; production settings are never used.
        try(WebDiagnosticLogService service = new WebDiagnosticLogService(() -> logs, preferences(node)))
        {
            new WebSessionHttpController(access, authentication, security).register(server);
            WebDiagnosticLogHttpController controller = new WebDiagnosticLogHttpController(service);
            server.createContext(ROOT, security.protectApi(WebCapability.ADMIN_SETTINGS, controller::handle));
            server.start();
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            Login admin = login(client, origin, "admin", ADMIN_PASSWORD);
            Login listener = login(client, origin, "listener", USER_PASSWORD);

            assertFalse(service.status().enabled());
            HttpResponse<byte[]> missingCsrf = put(client, origin, admin, origin.toString(), null, true);
            assertEquals(403, missingCsrf.statusCode(), text(missingCsrf));
            HttpResponse<byte[]> foreignOrigin = put(client, origin, admin, "https://foreign.example.invalid", admin.csrf(), true);
            assertEquals(403, foreignOrigin.statusCode(), text(foreignOrigin));
            HttpResponse<byte[]> missingOrigin = put(client, origin, admin, null, admin.csrf(), true);
            assertEquals(403, missingOrigin.statusCode(), text(missingOrigin));
            assertFalse(service.status().enabled());
            assertFalse(preferences(node).isWebDiagnosticLoggingEnabled());

            HttpResponse<byte[]> enabled = put(client, origin, admin, origin.toString(), admin.csrf(), true);
            assertEquals(200, enabled.statusCode(), text(enabled));
            assertNoCache(enabled);
            assertTrue(MAPPER.readTree(enabled.body()).at("/data/enabled").asBoolean());
            assertTrue(preferences(node).isWebDiagnosticLoggingEnabled());
            service.event("listener_started", Map.of("operation", "diagnostic-test-operation"));
            awaitLog(logs, "diagnostic-test-operation");

            for(String path: List.of(ROOT, ROOT + "/entries?log=current", ROOT + "/download"))
            {
                HttpResponse<byte[]> anonymous = get(client, origin.resolve(path), null);
                assertEquals(401, anonymous.statusCode(), text(anonymous));
                assertNoCache(anonymous);
                assertFalse(text(anonymous).contains("diagnostic-test-operation"));
                HttpResponse<byte[]> forbidden = get(client, origin.resolve(path), listener.cookie());
                assertEquals(403, forbidden.statusCode(), text(forbidden));
                assertNoCache(forbidden);
                assertFalse(text(forbidden).contains("diagnostic-test-operation"));
                HttpResponse<byte[]> allowed = get(client, origin.resolve(path), admin.cookie());
                assertEquals(200, allowed.statusCode(), text(allowed));
                assertNoCache(allowed);
                if(path.endsWith("/download"))
                {
                    assertEquals("application/zip", allowed.headers().firstValue("Content-Type").orElse(null));
                    assertTrue(allowed.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment;"));
                    try(ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(allowed.body())))
                    {
                        assertEquals(WebDiagnosticLogService.FILE_NAME, zip.getNextEntry().getName());
                        assertTrue(new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains("diagnostic-test-operation"));
                    }
                }
                else if(path.contains("/entries"))
                {
                    JsonNode snapshot = MAPPER.readTree(allowed.body()).path("data");
                    assertTrue(snapshot.path("available").asBoolean(), text(allowed));
                    assertEquals(WebDiagnosticLogService.FILE_NAME, snapshot.path("file_name").asText());
                    assertTrue(snapshot.path("entries").toString().contains("diagnostic-test-operation"));
                }
            }

            HttpResponse<byte[]> forbiddenChange = put(client, origin, listener, origin.toString(), listener.csrf(), false);
            assertEquals(403, forbiddenChange.statusCode(), text(forbiddenChange));
            assertTrue(service.status().enabled());
            HttpResponse<byte[]> disabled = put(client, origin, admin, origin.toString(), admin.csrf(), false);
            assertEquals(200, disabled.statusCode(), text(disabled));
            assertFalse(service.status().enabled());
            assertFalse(preferences(node).isWebDiagnosticLoggingEnabled());

            // Password recovery revokes the old reader session; another poll or download must reauthorize it.
            char[] replacement = "diagnostic-admin-replacement-password".toCharArray();
            try { access.provisionOrResetPrimaryAdmin(replacement); }
            finally { Arrays.fill(replacement, '\u0000'); }
            for(String path: List.of(ROOT, ROOT + "/entries", ROOT + "/download"))
            {
                HttpResponse<byte[]> revoked = get(client, origin.resolve(path), admin.cookie());
                assertEquals(401, revoked.statusCode(), text(revoked));
                assertNoCache(revoked);
                assertFalse(text(revoked).contains("diagnostic-test-operation"));
            }
            assertEquals(401, put(client, origin, admin, origin.toString(), admin.csrf(), true).statusCode());
            assertFalse(service.status().enabled());
        }
        finally
        {
            server.stop(0);
            executor.shutdownNow();
            security.close();
            node.removeNode();
        }
    }

    private static ApplicationPreference preferences(Preferences node) throws ReflectiveOperationException
    {
        ApplicationPreference preference = new ApplicationPreference(ignored -> {});
        Field field = ApplicationPreference.class.getDeclaredField("mPreferences");
        field.setAccessible(true);
        field.set(preference, node);
        return preference;
    }

    private static HttpResponse<byte[]> get(HttpClient client, URI target, String cookie) throws Exception
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5));
        if(cookie != null) request.header("Cookie", cookie);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpResponse<byte[]> put(HttpClient client, URI origin, Login login, String requestOrigin,
                                          String csrf, boolean enabled) throws Exception
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(ROOT)).timeout(Duration.ofSeconds(5))
            .header("Cookie", login.cookie()).header("Content-Type", "application/json");
        if(requestOrigin != null) request.header("Origin", requestOrigin);
        if(csrf != null) request.header(WebRequestSecurity.CSRF_HEADER_NAME, csrf);
        return client.send(request.PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\":" + enabled + "}")).build(),
            HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Login login(HttpClient client, URI origin, String username, String password) throws Exception
    {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/login"))
            .timeout(Duration.ofSeconds(5)).header("Origin", origin.toString()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(Map.of("username", username, "password", password))))
            .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), text(response));
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return new Login(setCookie.substring(0, setCookie.indexOf(';')),
            MAPPER.readTree(response.body()).at("/data/csrf_token").asText());
    }

    private static void assertNoCache(HttpResponse<byte[]> response)
    {
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("Cookie", response.headers().firstValue("Vary").orElse(null));
    }

    private static String text(HttpResponse<byte[]> response)
    {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static void awaitLog(Path directory, String marker) throws Exception
    {
        Path file = directory.resolve(WebDiagnosticLogService.FILE_NAME);
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        do
        {
            if(Files.isRegularFile(file) && Files.readString(file).contains(marker)) return;
            Thread.sleep(10);
        }
        while(System.nanoTime() < deadline);
        assertTrue(Files.isRegularFile(file) && Files.readString(file).contains(marker));
    }

    private record Login(String cookie, String csrf) { }
}
