/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.http.WebAccessHttpController;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebReceiverHealthAccessTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void exposesReceiverHealthOnlyToAnAuthenticatedAdministrator() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        char[] adminPassword = "receiver-health-admin-password".toCharArray();
        char[] userPassword = "receiver-health-user-password".toCharArray();

        try
        {
            accessService.provisionOrResetPrimaryAdmin(adminPassword);
            accessService.createUser("listener", userPassword, AccessTier.USER);
        }
        finally
        {
            Arrays.fill(adminPassword, '\u0000');
            Arrays.fill(userPassword, '\u0000');
        }

        WebAccessHttpController accessController = new WebAccessHttpController(accessService);
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        accessController.register(server);
        new StatsApiV1Controller(null, Map::of, accessController, null,
            () -> Map.of("summary", Map.of("severity", "healthy"))).register(server);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            URI target = origin.resolve(StatsApiV1.RECEIVER_HEALTH);
            HttpClient client = HttpClient.newHttpClient();
            assertEquals(401, client.send(HttpRequest.newBuilder(target).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
            String userCookie = login(client, origin, "listener", "receiver-health-user-password");
            assertEquals(403, client.send(HttpRequest.newBuilder(target).header("Cookie", userCookie).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
            String adminCookie = login(client, origin, "admin", "receiver-health-admin-password");
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(target).header("Cookie", adminCookie)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("healthy", OBJECT_MAPPER.readTree(response.body()).at("/data/summary/severity").textValue());
            assertThrows(IllegalArgumentException.class,
                () -> accessService.setCapabilityTier(WebCapability.RECEIVER_HEALTH, AccessTier.PUBLIC));
            assertFalse(WebCapability.RECEIVER_HEALTH.configurable());
        }
        finally
        {
            server.stop(0);
            executor.shutdownNow();
            accessController.close();
        }
    }

    private static String login(HttpClient client, URI origin, String username, String password) throws Exception
    {
        String body = OBJECT_MAPPER.writeValueAsString(Map.of("username", username, "password", password));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/login"))
            .header("Origin", origin.toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }
}
