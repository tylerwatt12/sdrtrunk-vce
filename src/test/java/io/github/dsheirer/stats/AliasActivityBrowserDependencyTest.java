/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.http.AliasAdminHttpController;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Measures the complete two-stage dependency chain awaited by the browser before it renders Alias Activity. */
class AliasActivityBrowserDependencyTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void coldAndRepeatedBrowserDependencyChainsMeetTargetsWithHundredThousandLoadedAliases() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("data");
        Path databasePath = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(databasePath.getParent());
        AliasActivityRepresentativeTestDatabase.Fixture fixture =
            AliasActivityRepresentativeTestDatabase.createExact(databasePath);
        insertAliasListsBeforeRepresentative(databasePath, 125);
        UserPreferences preferences = new TestUserPreferences(dataRoot);
        ConfigurationManager manager = new ConfigurationManager(preferences, null, new AliasModel(), null, null);
        manager.init();
        assertEquals(fixture.aliasCount(), manager.getAliasModel().getAliases().stream()
            .filter(alias -> alias.getAliasListId() == fixture.aliasListId()).count());

        WebAccessService accessService = new WebAccessService(databasePath);
        char[] password = "alias-browser-performance-password".toCharArray();

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
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        new WebSessionHttpController(accessService, authenticationService, requestSecurity).register(server);
        AliasAdministrationService administration = AliasAdministrationServiceTestSupport.create(manager);
        AliasAdminHttpController adminController = new AliasAdminHttpController(administration);
        server.createContext(AliasAdminHttpController.ALIAS_LISTS_PATH, requestSecurity.protectApi(
            WebCapability.ADMIN_ALIASES, adminController::handle));
        server.createContext(AliasAdminHttpController.ALIASES_PATH, requestSecurity.protectApi(
            WebCapability.ADMIN_ALIASES, adminController::handle));
        StatsWebDatabase database = new StatsWebDatabase(preferences, databasePath);
        new StatsApiV1Controller(database, Map::of, requestSecurity, null).register(server);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String session = login(client, origin, "admin", "alias-browser-performance-password");

            TimedChain cold = activityRenderChain(client, origin, session, fixture.aliasListId(),
                "logical_call_count", 0, null);
            assertEquals(fixture.aliasCount(), cold.publicAliasCount());
            assertTrue(cold.publicAliasListRows() > 100,
                "The browser catalog must preserve database counts beyond the default 100-row API page");
            assertEquals(100, cold.pageRows());
            assertFalse(cold.adminCountsPresent());
            assertEquals(0, cold.groupSuggestionCount());
            assertTrue(cold.elapsedMillis() < 2_000,
                "Cold 100,000-Alias browser dependency chain took " + cold.elapsedMillis() + " ms");

            TimedChain sortedPage = activityRenderChain(client, origin, session, fixture.aliasListId(),
                "signaling_observation_count", 10_000, null);
            assertEquals(100, sortedPage.pageRows());
            assertTrue(sortedPage.elapsedMillis() < 1_000,
                "Repeated sorted/paged browser dependency chain took " + sortedPage.elapsedMillis() + " ms");

            TimedChain filtered = activityRenderChain(client, origin, session, fixture.aliasListId(),
                "last_evidence_ms", 0, "Dispatch");
            assertEquals(100, filtered.pageRows());
            assertTrue(filtered.elapsedMillis() < 1_000,
                "Repeated filtered browser dependency chain took " + filtered.elapsedMillis() + " ms");

            System.out.printf("ALIAS_BROWSER_CHAIN cold=%dms sorted_page=%dms filtered=%dms aliases=%d lists=%d%n",
                cold.elapsedMillis(), sortedPage.elapsedMillis(), filtered.elapsedMillis(), fixture.aliasCount(),
                cold.publicAliasListRows());
        }
        finally
        {
            server.stop(0);
            executor.shutdownNow();
            requestSecurity.close();
            closeConfigurationManager(manager);
        }
    }

    private static TimedChain activityRenderChain(HttpClient client, URI origin, String session,
                                                   long aliasListId, String sort, int offset, String group)
        throws Exception
    {
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<String>> publicLists = get(client, origin,
            StatsApiV1.ALIAS_LISTS + "?limit=500", session);
        CompletableFuture<HttpResponse<String>> adminLists = get(client, origin,
            AliasAdminHttpController.ALIAS_LISTS_PATH + "?include_counts=false", session);
        JsonNode publicData = data(publicLists.get(10, TimeUnit.SECONDS));
        JsonNode adminData = data(adminLists.get(10, TimeUnit.SECONDS));
        StringBuilder pagePath = new StringBuilder(StatsApiV1.ALIASES).append("?list=").append(aliasListId)
            .append("&sort=").append(sort).append("&direction=desc&limit=100&offset=").append(offset);

        if(group != null)
        {
            pagePath.append("&group=").append(group);
        }

        CompletableFuture<HttpResponse<String>> page = get(client, origin, pagePath.toString(), session);
        CompletableFuture<HttpResponse<String>> options = get(client, origin,
            AliasAdminHttpController.OPTIONS_PATH + "?alias_list_id=" + aliasListId +
                "&include_group_names=false", session);
        JsonNode pageData = data(page.get(10, TimeUnit.SECONDS));
        JsonNode optionData = data(options.get(10, TimeUnit.SECONDS));
        JsonNode publicList = findList(publicData, aliasListId, "alias_list_id");
        JsonNode adminList = findList(adminData.get("alias_lists"), aliasListId, "alias_list_id");
        return new TimedChain(elapsedMillis(started), publicData.size(), publicList.get("alias_count").intValue(),
            pageData.size(), adminList.has("alias_count") || adminList.has("assigned_channel_count"),
            optionData.get("group_names").size());
    }

    private static void insertAliasListsBeforeRepresentative(Path databasePath, int count) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO alias_list(id,name,family,unmatched_talkgroup_record_enabled) VALUES (?,?,'DMR',0)
                """))
        {
            for(int index = 0; index < count; index++)
            {
                statement.setLong(1, 20_000L + index);
                statement.setString(2, "000 Browser filler " + String.format("%03d", index));
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private static CompletableFuture<HttpResponse<String>> get(HttpClient client, URI origin, String path,
                                                                String session)
    {
        return client.sendAsync(HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(10))
            .header("Cookie", session).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception
    {
        assertEquals(200, response.statusCode(), response.body());
        return OBJECT_MAPPER.readTree(response.body()).get("data");
    }

    private static JsonNode findList(JsonNode rows, long aliasListId, String idField)
    {
        return java.util.stream.StreamSupport.stream(rows.spliterator(), false)
            .filter(row -> row.get(idField).longValue() == aliasListId).findFirst().orElseThrow();
    }

    private static String login(HttpClient client, URI origin, String username, String password) throws Exception
    {
        String body = OBJECT_MAPPER.writeValueAsString(Map.of("username", username, "password", password));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/login"))
            .timeout(Duration.ofSeconds(10))
            .header("Origin", origin.toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private static long elapsedMillis(long started)
    {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static void closeConfigurationManager(ConfigurationManager manager)
    {
        var channelProcessingManager = manager.getChannelProcessingManager();
        MyEventBus.getGlobalEventBus().unregister(channelProcessingManager);
        channelProcessingManager.close();
    }

    private record TimedChain(long elapsedMillis, int publicAliasListRows, int publicAliasCount, int pageRows,
                              boolean adminCountsPresent, int groupSuggestionCount)
    {
    }

    private static final class TestUserPreferences extends UserPreferences
    {
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(Path dataRoot)
        {
            mDirectoryPreference = new DirectoryPreference(_ -> {})
            {
                @Override
                public Path getDirectoryApplicationRoot()
                {
                    return dataRoot;
                }
            };
        }

        @Override
        public DirectoryPreference getDirectoryPreference()
        {
            return mDirectoryPreference;
        }
    }
}
