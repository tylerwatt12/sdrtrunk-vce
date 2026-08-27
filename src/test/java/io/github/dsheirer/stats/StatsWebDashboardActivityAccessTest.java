/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.http.WebRequestSecurity;
import io.github.dsheirer.web.http.WebSessionHttpController;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebDashboardActivityAccessTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void enforcesSeparateActivityActionAndRadioCapabilities() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        char[] adminPassword = "dashboard-activity-admin-password".toCharArray();
        char[] password = "dashboard-activity-user-password".toCharArray();

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
        WebAuthenticationService authenticationService = new WebAuthenticationService(accessService);
        WebRequestSecurity requestSecurity = new WebRequestSecurity(accessService, authenticationService);
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        new WebSessionHttpController(accessService, authenticationService, requestSecurity).register(server);
        StatsWebDatabase statsDatabase = new StatsWebDatabase(new UserPreferences(), database);
        new StatsApiV1Controller(statsDatabase, Map::of, requestSecurity, null).register(server);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> actions = get(client, origin,
                StatsApiV1.ACTIVITY_ACTIONS + "?range=24h", null);
            assertEquals(200, actions.statusCode(), actions.body());

            HttpResponse<String> anonymousRadios = get(client, origin,
                StatsApiV1.ACTIVITY_RADIOS + "?range=24h&action=GRANT", null);
            assertEquals(401, anonymousRadios.statusCode(), anonymousRadios.body());
            JsonNode error = OBJECT_MAPPER.readTree(anonymousRadios.body());
            assertEquals("authentication_required", error.at("/error/code").textValue(), anonymousRadios.body());

            String cookie = login(client, origin, "listener", "dashboard-activity-user-password");
            HttpResponse<String> authenticatedRadios = get(client, origin,
                StatsApiV1.ACTIVITY_RADIOS + "?range=24h&action=GRANT", cookie);
            assertEquals(200, authenticatedRadios.statusCode(), authenticatedRadios.body());
            assertEquals("grant", OBJECT_MAPPER.readTree(authenticatedRadios.body())
                .at("/meta/action").textValue(), authenticatedRadios.body());
        }
        finally
        {
            server.stop(0);
            executor.shutdownNow();
            requestSecurity.close();
        }
    }

    @Test
    void admitsOnlyOneExactActivityRadioQueryAtATime() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("single-flight.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        char[] adminPassword = "dashboard-activity-single-flight-admin-password".toCharArray();

        try
        {
            accessService.provisionOrResetPrimaryAdmin(adminPassword);
        }
        finally
        {
            Arrays.fill(adminPassword, '\u0000');
        }

        accessService.setCapabilityTier(WebCapability.SYSTEMS_VIEW, AccessTier.PUBLIC);
        WebAuthenticationService authenticationService = new WebAuthenticationService(accessService);
        WebRequestSecurity requestSecurity = new WebRequestSecurity(accessService, authenticationService);
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        server.setExecutor(executor);
        BlockingStatsWebDatabase statsDatabase =
            new BlockingStatsWebDatabase(new UserPreferences(), database);
        new StatsApiV1Controller(statsDatabase, Map::of, requestSecurity, null).register(server);
        server.start();
        CompletableFuture<HttpResponse<String>> firstResponse = null;

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(origin.resolve(
                StatsApiV1.ACTIVITY_RADIOS + "?range=24h&action=GRANT")).GET().build();
            firstResponse = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            assertTrue(statsDatabase.awaitQueryStart(), "The first exact query did not start");

            HttpResponse<String> busy = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(429, busy.statusCode(), busy.body());
            JsonNode error = OBJECT_MAPPER.readTree(busy.body());
            assertEquals("activity_query_busy", error.at("/error/code").textValue(), busy.body());
            assertEquals(1, statsDatabase.queryCount());

            statsDatabase.releaseQuery();
            HttpResponse<String> completed = firstResponse.get(5, TimeUnit.SECONDS);
            assertEquals(200, completed.statusCode(), completed.body());

            HttpResponse<String> afterRelease = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, afterRelease.statusCode(), afterRelease.body());
            assertEquals(2, statsDatabase.queryCount());
        }
        finally
        {
            statsDatabase.releaseQuery();

            if(firstResponse != null)
            {
                firstResponse.cancel(true);
            }

            server.stop(0);
            executor.shutdownNow();
            requestSecurity.close();
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI origin, String path, String cookie)
        throws Exception
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(path)).GET();

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

    private static final class BlockingStatsWebDatabase extends StatsWebDatabase
    {
        private final CountDownLatch mQueryStarted = new CountDownLatch(1);
        private final CountDownLatch mReleaseQuery = new CountDownLatch(1);
        private final AtomicInteger mQueryCount = new AtomicInteger();

        private BlockingStatsWebDatabase(UserPreferences userPreferences, Path databasePath)
        {
            super(userPreferences, databasePath);
        }

        @Override
        Map<String,Object> dashboardActivityRadios(StatsRequest request)
        {
            request.text("range");
            request.requiredText("action");
            request.limit();
            request.longOffset();
            mQueryCount.incrementAndGet();
            mQueryStarted.countDown();

            try
            {
                mReleaseQuery.await();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to release the exact query", exception);
            }

            return Map.of("rows", List.of());
        }

        private boolean awaitQueryStart() throws InterruptedException
        {
            return mQueryStarted.await(5, TimeUnit.SECONDS);
        }

        private void releaseQuery()
        {
            mReleaseQuery.countDown();
        }

        private int queryCount()
        {
            return mQueryCount.get();
        }
    }
}
