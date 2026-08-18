/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.UserPreferences;
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

class StatsWebActivityAnalyticsAccessTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void requiresSystemsAccessForRetainedActivityDetails() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        char[] adminPassword = "activity-analytics-admin-password".toCharArray();
        char[] password = "activity-analytics-user-password".toCharArray();

        try
        {
            accessService.provisionOrResetPrimaryAdmin(adminPassword);
            accessService.createUser("listener", password, AccessTier.USER);
        }
        finally
        {
            Arrays.fill(adminPassword, '\u0000');
            Arrays.fill(password, '\u0000');
        }

        accessService.setCapabilityTier(WebCapability.DASHBOARD_VIEW, AccessTier.PUBLIC);
        accessService.setCapabilityTier(WebCapability.SYSTEMS_VIEW, AccessTier.USER);
        WebAccessHttpController accessController = new WebAccessHttpController(accessService);
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        accessController.register(server);
        StatsWebDatabase statsDatabase = new StatsWebDatabase(new UserPreferences(), database);
        new StatsApiV1Controller(statsDatabase, Map::of, accessController, null).register(server);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> summary = get(client, origin,
                "?range=24h&group_by=action", null);
            assertEquals(200, summary.statusCode(), summary.body());

            HttpResponse<String> anonymousDetails = get(client, origin,
                "?range=24h&group_by=radio&action=EMERGENCY", null);
            assertEquals(403, anonymousDetails.statusCode(), anonymousDetails.body());
            JsonNode error = OBJECT_MAPPER.readTree(anonymousDetails.body());
            assertEquals("access_denied", error.at("/error/code").textValue(), anonymousDetails.body());

            String cookie = login(client, origin, "listener", "activity-analytics-user-password");
            HttpResponse<String> authenticatedDetails = get(client, origin,
                "?range=24h&group_by=radio&action=EMERGENCY", cookie);
            assertEquals(200, authenticatedDetails.statusCode(), authenticatedDetails.body());
            assertEquals("radio", OBJECT_MAPPER.readTree(authenticatedDetails.body())
                .at("/data/group_by").textValue(), authenticatedDetails.body());
        }
        finally
        {
            server.stop(0);
            executor.shutdownNow();
            accessController.close();
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI origin, String query, String cookie)
        throws Exception
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(StatsApiV1.ACTIVITY_ANALYTICS + query))
            .GET();

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
            .header("Origin", origin.toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }
}
