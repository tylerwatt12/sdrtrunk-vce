/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.support.ApplicationLogService;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.http.ApplicationLogHttpController;
import io.github.dsheirer.web.http.WebRequestSecurity;
import io.github.dsheirer.web.http.WebSessionHttpController;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebApplicationLogAccessTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void applicationLogsRequireAnAdministratorAndNeverCacheSensitiveOutput() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        char[] adminPassword = "application-log-admin-password".toCharArray();
        char[] listenerPassword = "application-log-listener-password".toCharArray();

        try
        {
            accessService.provisionOrResetPrimaryAdmin(adminPassword);
            accessService.createUser("listener", listenerPassword);
        }
        finally
        {
            Arrays.fill(adminPassword, '\u0000');
            Arrays.fill(listenerPassword, '\u0000');
        }

        Path logDirectory = Files.createDirectories(mTemporaryDirectory.resolve("logs"));
        Files.writeString(logDirectory.resolve("sdrtrunk_app.log"), """
            20261002 101500.125 [main] ERROR io.github.dsheirer.example.Sample - Sample startup failed
            java.lang.IllegalStateException: Unable to load /Users/sample-user/config.xml
            Suppressed: java.lang.IllegalArgumentException: password=sample-secret api%5Fkey=sample-encoded-key endpoint=https://receiver.example.invalid:8090/api
                at io.github.dsheirer.example.Sample.start(Sample.java:42)
            Caused by: java.io.IOException: Sample resource unavailable
                at io.github.dsheirer.example.Sample.read(Sample.java:17)
            """);

        WebAuthenticationService authenticationService = new WebAuthenticationService(accessService);
        WebRequestSecurity requestSecurity = new WebRequestSecurity(accessService, authenticationService);
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);

        try(ApplicationLogService logService = new ApplicationLogService(() -> logDirectory))
        {
            new WebSessionHttpController(accessService, authenticationService, requestSecurity).register(server);
            ApplicationLogHttpController controller = new ApplicationLogHttpController(logService);
            server.createContext(ApplicationLogHttpController.PATH,
                requestSecurity.protectApi(WebCapability.ADMIN_SETTINGS, controller::handle));
            server.start();

            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            URI target = origin.resolve(ApplicationLogHttpController.PATH);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> unauthenticated = read(client, target, null);
            assertEquals(401, unauthenticated.statusCode(), unauthenticated.body());
            assertFalse(unauthenticated.body().contains("Sample startup failed"));

            String listenerCookie = login(client, origin, "listener", "application-log-listener-password");
            HttpResponse<String> forbidden = read(client, target, listenerCookie);
            assertEquals(403, forbidden.statusCode(), forbidden.body());
            assertFalse(forbidden.body().contains("Sample startup failed"));

            String adminCookie = login(client, origin, "admin", "application-log-admin-password");
            HttpResponse<String> response = readLoadedLog(client, target, adminCookie);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
            JsonNode data = OBJECT_MAPPER.readTree(response.body()).path("data");
            assertTrue(data.path("available").asBoolean(), response.body());
            assertEquals("sdrtrunk_app.log", data.path("file_name").asText());
            assertEquals(1, data.path("entries").size(), response.body());
            JsonNode entry = data.path("entries").get(0);
            assertEquals("ERROR", entry.path("level").asText());
            assertTrue(entry.path("message").asText().contains("Sample startup failed"));
            assertTrue(entry.path("details").toString().contains("Sample.java:42"));
            assertTrue(entry.path("details").toString().contains("Caused by: java.io.IOException"));
            assertTrue(entry.path("details").toString().contains("Sample.java:17"));
            assertFalse(response.body().contains("sample-secret"));
            assertFalse(response.body().contains("sample-encoded-key"));
            assertFalse(response.body().contains("sample-user"));
            assertFalse(response.body().contains("receiver.example.invalid"));
            assertThrows(IllegalArgumentException.class,
                () -> accessService.setCapabilityTier(WebCapability.ADMIN_SETTINGS, AccessTier.PUBLIC));
            assertFalse(WebCapability.ADMIN_SETTINGS.configurable());
        }
        finally
        {
            server.stop(0);
            executor.shutdownNow();
            requestSecurity.close();
        }
    }

    private static HttpResponse<String> readLoadedLog(HttpClient client, URI target, String cookie) throws Exception
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        HttpResponse<String> response;

        do
        {
            response = read(client, target, cookie);
            assertEquals(200, response.statusCode(), response.body());

            if(!OBJECT_MAPPER.readTree(response.body()).at("/data/entries").isEmpty())
            {
                return response;
            }

            Thread.sleep(25);
        }
        while(System.nanoTime() < deadline);

        return response;
    }

    private static HttpResponse<String> read(HttpClient client, URI target, String cookie) throws Exception
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5)).GET();

        if(cookie != null)
        {
            request.header("Cookie", cookie);
        }

        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String login(HttpClient client, URI origin, String username, String password) throws Exception
    {
        String body = OBJECT_MAPPER.writeValueAsString(Map.of("username", username, "password", password));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/login"))
            .timeout(Duration.ofSeconds(5))
            .header("Origin", origin.toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }
}
