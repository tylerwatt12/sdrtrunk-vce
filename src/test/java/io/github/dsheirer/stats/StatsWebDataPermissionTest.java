/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebDataPermissionTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CONFIGURATION_ID = "728d2d66-de4e-476b-a696-919f32dd4d12";

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void csvExportRequiresExportAndDatasetCapabilities() throws Exception
    {
        try(TestServer server = startServer("csv-permissions.sqlite"))
        {
            server.access().setCapabilityTier(WebCapability.CSV_EXPORT, AccessTier.PUBLIC);
            server.access().setCapabilityTier(WebCapability.SYSTEMS_VIEW, AccessTier.USER);
            server.access().setCapabilityTier(WebCapability.CONVENTIONAL_VIEW, AccessTier.PUBLIC);

            assertEquals(200, server.get(export("conventional-channels"), null).statusCode());
            assertEquals(403, server.get(export("system-talkgroups") + "?scope=p25:BEE00:49F", null).statusCode());

            String listener = server.login();
            assertEquals(404,
                server.get(export("system-talkgroups") + "?scope=p25:BEE00:49F", listener).statusCode());

            server.access().setCapabilityTier(WebCapability.CSV_EXPORT, AccessTier.USER);
            assertEquals(403, server.get(export("conventional-channels"), null).statusCode());
            assertEquals(200, server.get(export("conventional-channels"), listener).statusCode());
        }
    }

    @Test
    void activityUsesTheCapabilityForItsRequestedScope() throws Exception
    {
        try(TestServer server = startServer("activity-permissions.sqlite"))
        {
            server.access().setCapabilityTier(WebCapability.SYSTEMS_VIEW, AccessTier.USER);
            server.access().setCapabilityTier(WebCapability.CONVENTIONAL_VIEW, AccessTier.PUBLIC);

            assertEquals(404,
                server.get(StatsApiV1.ACTIVITY + "?configuration_id=" + CONFIGURATION_ID, null).statusCode());
            assertEquals(403, server.get(StatsApiV1.ACTIVITY + "?scope=p25:BEE00:49F", null).statusCode());
            assertEquals(403, server.get(StatsApiV1.ACTIVITY, null).statusCode());

            String listener = server.login();
            assertEquals(200,
                server.get(StatsApiV1.ACTIVITY + "?scope=p25:BEE00:49F", listener).statusCode());

            HttpResponse<String> mixed = server.get(
                StatsApiV1.ACTIVITY + "?configuration_id=" + CONFIGURATION_ID + "&scope=p25:BEE00:49F", null);
            assertEquals(400, mixed.statusCode(), mixed.body());
            assertEquals("invalid_parameter", OBJECT_MAPPER.readTree(mixed.body()).at("/error/code").textValue());

            server.access().setCapabilityTier(WebCapability.SYSTEMS_VIEW, AccessTier.PUBLIC);
            server.access().setCapabilityTier(WebCapability.CONVENTIONAL_VIEW, AccessTier.USER);
            assertEquals(200, server.get(StatsApiV1.ACTIVITY + "?scope=p25:BEE00:49F", null).statusCode());
            assertEquals(403,
                server.get(StatsApiV1.ACTIVITY + "?configuration_id=" + CONFIGURATION_ID, null).statusCode());
            assertEquals(404,
                server.get(StatsApiV1.ACTIVITY + "?configuration_id=" + CONFIGURATION_ID, listener).statusCode());
        }
    }

    private TestServer startServer(String databaseName) throws Exception
    {
        Path database = mTemporaryDirectory.resolve(databaseName);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebAccessService accessService = new WebAccessService(database);
        char[] adminPassword = "data-permission-admin-password".toCharArray();
        char[] listenerPassword = "data-permission-listener-password".toCharArray();

        try
        {
            accessService.provisionOrResetPrimaryAdmin(adminPassword);
            accessService.createUser("listener", listenerPassword, AccessTier.USER);
        }
        finally
        {
            Arrays.fill(adminPassword, '\u0000');
            Arrays.fill(listenerPassword, '\u0000');
        }

        WebAuthenticationService authenticationService = new WebAuthenticationService(accessService);
        WebRequestSecurity requestSecurity = new WebRequestSecurity(accessService, authenticationService);
        HttpServer httpServer = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        httpServer.setExecutor(executor);
        new WebSessionHttpController(accessService, authenticationService, requestSecurity).register(httpServer);
        StatsWebDatabase statsDatabase = new StatsWebDatabase(new UserPreferences(), database);
        new StatsApiV1Controller(statsDatabase, Map::of, requestSecurity, null).register(httpServer);
        httpServer.start();
        return new TestServer(httpServer, executor, requestSecurity, accessService);
    }

    private static String export(String dataset)
    {
        return StatsApiV1.EXPORTS + "/" + dataset + ".csv";
    }

    private record TestServer(HttpServer server, ExecutorService executor, WebRequestSecurity security,
                              WebAccessService access) implements AutoCloseable
    {
        private URI origin()
        {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private HttpResponse<String> get(String path, String cookie) throws Exception
        {
            HttpRequest.Builder request = HttpRequest.newBuilder(origin().resolve(path)).GET();

            if(cookie != null)
            {
                request.header("Cookie", cookie);
            }

            return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        private String login() throws Exception
        {
            String body = OBJECT_MAPPER.writeValueAsString(Map.of(
                "username", "listener", "password", "data-permission-listener-password"));
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(origin().resolve("/api/v1/auth/login"))
                    .header("Origin", origin().toString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
            return setCookie.substring(0, setCookie.indexOf(';'));
        }

        @Override
        public void close()
        {
            server.stop(0);
            executor.shutdownNow();
            security.close();
        }
    }

}
