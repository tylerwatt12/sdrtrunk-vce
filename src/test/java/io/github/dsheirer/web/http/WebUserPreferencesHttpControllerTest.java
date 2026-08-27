/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.auth.WebUserPreferencesService;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebUserPreferencesHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "admin-preference-password";

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void usesSessionIdentityAndOptimisticFullDocumentUpdates() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        char[] password = PASSWORD.toCharArray();
        try
        {
            accessService.provisionOrResetPrimaryAdmin(password);
        }
        finally
        {
            Arrays.fill(password, '\u0000');
        }

        WebAuthenticationService authenticationService = new WebAuthenticationService(accessService);
        WebRequestSecurity requestSecurity = new WebRequestSecurity(accessService, authenticationService);
        WebUserPreferencesHttpController preferences = new WebUserPreferencesHttpController(requestSecurity,
            new WebUserPreferencesService(database));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        new WebSessionHttpController(accessService, authenticationService, requestSecurity).register(server);
        server.createContext(WebUserPreferencesHttpController.PATH,
            requestSecurity.protectApi(WebCapability.USER_SETTINGS, preferences::handle));
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            assertEquals(401, send(client, request(origin).GET()).statusCode());
            Login login = login(client, origin);

            HttpResponse<String> initialResponse = send(client, request(origin)
                .header("Cookie", login.cookie()).GET());
            assertEquals(200, initialResponse.statusCode(), initialResponse.body());
            assertEquals("\"1\"", initialResponse.headers().firstValue("ETag").orElseThrow());
            JsonNode initial = MAPPER.readTree(initialResponse.body());
            assertFalse(initial.has("data"));
            assertEquals(1, initial.path("revision").longValue());
            assertEquals("light", initial.at("/preferences/appearance/theme").textValue());
            assertTrue(initial.at("/preferences/playback/selected_scan_list_ids").isArray());

            JsonNode replacement = initial.path("preferences").deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)replacement.path("page_titles"))
                .put("prepend_playing_call", true);
            String replacementJson = MAPPER.writeValueAsString(replacement);

            assertEquals(403, send(client, request(origin)
                .header("Origin", origin.toString()).header("Cookie", login.cookie())
                .header("Content-Type", "application/json").header("If-Match", "\"1\"")
                .PUT(HttpRequest.BodyPublishers.ofString(replacementJson))).statusCode());

            HttpResponse<String> updatedResponse = send(client, mutation(origin, login)
                .header("If-Match", "\"1\"")
                .PUT(HttpRequest.BodyPublishers.ofString(replacementJson)));
            assertEquals(200, updatedResponse.statusCode(), updatedResponse.body());
            JsonNode updated = MAPPER.readTree(updatedResponse.body());
            assertEquals(2, updated.path("revision").longValue());
            assertTrue(updated.at("/preferences/page_titles/prepend_playing_call").booleanValue());
            assertEquals("\"2\"", updatedResponse.headers().firstValue("ETag").orElseThrow());
            assertEquals(1, accessService.primaryAdmin().orElseThrow().authRevision());

            HttpResponse<String> stale = send(client, mutation(origin, login)
                .header("If-Match", "\"1\"")
                .PUT(HttpRequest.BodyPublishers.ofString(replacementJson)));
            assertEquals(409, stale.statusCode());
            assertEquals("preference_conflict", MAPPER.readTree(stale.body()).at("/error/code").textValue());
            assertEquals("\"2\"", stale.headers().firstValue("ETag").orElseThrow());

            assertEquals(428, send(client, mutation(origin, login)
                .PUT(HttpRequest.BodyPublishers.ofString(replacementJson))).statusCode());
            assertEquals(400, send(client, mutation(origin, login).header("If-Match", "2")
                .PUT(HttpRequest.BodyPublishers.ofString(replacementJson))).statusCode());
            assertEquals(422, send(client, mutation(origin, login).header("If-Match", "\"2\"")
                .PUT(HttpRequest.BodyPublishers.ofString(replacementJson.replaceFirst("\\{", "{\"unknown\":true,"))))
                .statusCode());

            HttpResponse<String> stillSignedIn = send(client, request(origin)
                .header("Cookie", login.cookie()).GET());
            assertEquals(200, stillSignedIn.statusCode());
            assertEquals(2, MAPPER.readTree(stillSignedIn.body()).path("revision").longValue());
        }
        finally
        {
            server.stop(0);
            requestSecurity.close();
            executor.shutdownNow();
        }
    }

    private static Login login(HttpClient client, URI origin) throws Exception
    {
        String body = MAPPER.writeValueAsString(Map.of("username", "admin", "password", PASSWORD));
        HttpResponse<String> response = send(client, HttpRequest.newBuilder(
            origin.resolve(WebSessionHttpController.LOGIN_PATH)).timeout(Duration.ofSeconds(30))
            .header("Origin", origin.toString()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = MAPPER.readTree(response.body()).path("data");
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return new Login(setCookie.substring(0, setCookie.indexOf(';')), data.path("csrf_token").textValue());
    }

    private static HttpRequest.Builder request(URI origin)
    {
        return HttpRequest.newBuilder(origin.resolve(WebUserPreferencesHttpController.PATH))
            .timeout(Duration.ofSeconds(30));
    }

    private static HttpRequest.Builder mutation(URI origin, Login login)
    {
        return request(origin).header("Origin", origin.toString()).header("Cookie", login.cookie())
            .header(WebRequestSecurity.CSRF_HEADER_NAME, login.csrf())
            .header("Content-Type", "application/json");
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder builder) throws Exception
    {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Login(String cookie, String csrf)
    {
    }
}
