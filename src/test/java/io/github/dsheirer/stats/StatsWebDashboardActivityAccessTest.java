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
    void keepsActivityTotalsSeparateFromScopedRadioAccess() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        char[] adminPassword = "dashboard-activity-admin-password".toCharArray();
        char[] password = "dashboard-activity-user-password".toCharArray();

        try
        {
            accessService.provisionOrResetPrimaryAdmin(adminPassword);
            accessService.createUser("listener", password);
        }
        finally
        {
            Arrays.fill(adminPassword, '\u0000');
            Arrays.fill(password, '\u0000');
        }

        accessService.setCapabilityTier(WebCapability.DASHBOARD_VIEW, AccessTier.PUBLIC);
        accessService.setCapabilityTier(WebCapability.RADIO_VIEW, AccessTier.USER);
        WebAuthenticationService authenticationService = new WebAuthenticationService(accessService);
        WebRequestSecurity requestSecurity = new WebRequestSecurity(accessService, authenticationService);
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        new WebSessionHttpController(accessService, authenticationService, requestSecurity).register(server);
        StatsWebDatabase statsDatabase = new StatsWebDatabase(new UserPreferences(), database);
        AtomicInteger grantTiming = new AtomicInteger(2500);
        new StatsApiV1Controller(statsDatabase, Map::of, requestSecurity, null, Map::of, null,
            grantTiming::get).register(server);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> actions = get(client, origin,
                StatsApiV1.ACTIVITY_ACTIONS + "?range=24h", null);
            assertEquals(200, actions.statusCode(), actions.body());

            HttpResponse<String> anonymousActivity = get(client, origin, StatsApiV1.ACTIVITY + "?limit=1", null);
            assertEquals(401, anonymousActivity.statusCode(), anonymousActivity.body());
            String cookie = login(client, origin, "listener", "dashboard-activity-user-password");
            assertEquals(404, get(client, origin, "/api/v1/activity/radios?range=24h&action=GRANT", cookie).statusCode());

            accessService.setCapabilityTier(WebCapability.DASHBOARD_VIEW, AccessTier.ADMIN);
            HttpResponse<String> activity = get(client, origin, StatsApiV1.ACTIVITY + "?limit=1", cookie);
            assertEquals(200, activity.statusCode(), activity.body());
            assertEquals(2500, OBJECT_MAPPER.readTree(activity.body())
                .at("/meta/traffic_grant_age_out_milliseconds").intValue(), activity.body());
            grantTiming.set(1750);
            HttpResponse<String> updated = get(client, origin, StatsApiV1.ACTIVITY + "?limit=1", cookie);
            assertEquals(1750, OBJECT_MAPPER.readTree(updated.body())
                .at("/meta/traffic_grant_age_out_milliseconds").intValue(), updated.body());
            assertEquals(403, get(client, origin, StatsApiV1.STATUS, cookie).statusCode());
            assertEquals(403, get(client, origin, StatsApiV1.ACTIVITY_ACTIONS + "?range=24h", cookie).statusCode());
        }
        finally
        {
            server.stop(0);
            executor.shutdownNow();
            requestSecurity.close();
        }
    }

    @Test
    void twoHundredActivityVisitorsShareTheSamePreparedTotals() throws Exception
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

        accessService.setCapabilityTier(WebCapability.DASHBOARD_VIEW, AccessTier.PUBLIC);
        WebAuthenticationService authenticationService = new WebAuthenticationService(accessService);
        WebRequestSecurity requestSecurity = new WebRequestSecurity(accessService, authenticationService);
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        BlockingStatsWebDatabase statsDatabase =
            new BlockingStatsWebDatabase(new UserPreferences(), database);
        new StatsApiV1Controller(statsDatabase, Map::of, requestSecurity, null).register(server);
        server.start();
        List<CompletableFuture<HttpResponse<String>>> responses = new java.util.ArrayList<>();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(origin.resolve(
                StatsApiV1.ACTIVITY_ACTIONS + "?range=24h")).GET().build();
            responses.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
            assertTrue(statsDatabase.awaitQueryStart(), "The shared totals query did not start");
            for(int index = 1; index < 200; index++)
                responses.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
            statsDatabase.releaseQuery();
            String shared = responses.getFirst().get(5, TimeUnit.SECONDS).body();
            for(var response: responses)
            {
                HttpResponse<String> result = response.get(5, TimeUnit.SECONDS);
                assertEquals(200, result.statusCode(), result.body());
                assertEquals(shared, result.body());
            }
            assertEquals(1, statsDatabase.queryCount());
            assertEquals(200, get(client, origin, StatsApiV1.ACTIVITY_ACTIONS + "?range=1h", null).statusCode());
            assertEquals(2, statsDatabase.queryCount(), "Distinct time ranges must retain their own totals");
        }
        finally
        {
            statsDatabase.releaseQuery();
            responses.forEach(response -> response.cancel(true));
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
        Map<String,Object> dashboardActivityActions(StatsRequest request)
        {
            String range = request.text("range");
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

            return Map.of("rows", List.of(), "range", range, "from_ms", 0L, "to_ms", 1L,
                "total_observation_count", 0L);
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
