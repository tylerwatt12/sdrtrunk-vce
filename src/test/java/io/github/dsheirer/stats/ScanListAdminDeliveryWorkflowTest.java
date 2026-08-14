/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.scanlist.ScanListDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.scanlist.ScanListModel;
import io.github.dsheirer.web.http.AliasAdminHttpController;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the administrator-to-browser scan-list path as one persisted workflow. */
class ScanListAdminDeliveryWorkflowTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void createsPersistsFiltersAndDeduplicatesScanListsThroughTheAdminApi() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);
        manager.init();
        AliasAdministrationService administration = AliasAdministrationServiceTestSupport.create(manager);
        AliasAdminHttpController controller = new AliasAdminHttpController(administration);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(AliasAdminHttpController.ALIAS_LISTS_PATH, controller::handle);
        server.createContext(AliasAdminHttpController.ALIASES_PATH, controller::handle);
        server.createContext(AliasAdminHttpController.SCAN_LISTS_PATH, controller::handle);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            long revision = data(send(client, request(origin, AliasAdminHttpController.ALIAS_LISTS_PATH).GET()))
                .get("revision").longValue();

            Resource county = createAliasList(client, origin, revision, "County P25");
            Resource city = createAliasList(client, origin, county.revision(), "City P25");
            Resource southwest = createScanList(client, origin, city.revision(), "SouthWest", 1);
            Resource cleveland = createScanList(client, origin, southwest.revision(), "Cleveland", 2);
            Resource southwestDispatch = createAlias(client, origin, cleveland.revision(), county.id(),
                "SouthWest Dispatch", 1101, List.of(southwest.id()));
            Resource clevelandDispatch = createAlias(client, origin, southwestDispatch.revision(), city.id(),
                "Cleveland Dispatch", 2202, List.of(cleveland.id()));
            Resource mutualAid = createAlias(client, origin, clevelandDispatch.revision(), city.id(),
                "Mutual Aid", 3303, List.of(southwest.id(), cleveland.id()));

            Resource countyUnmatched = updateUnmatchedPolicy(client, origin, mutualAid.revision(), county.id(),
                List.of(southwest.id(), cleveland.id()));
            Resource cityUnmatched = updateUnmatchedPolicy(client, origin, countyUnmatched.revision(), city.id(),
                List.of(cleveland.id()));

            JsonNode aliasListCatalog = data(send(client,
                request(origin, AliasAdminHttpController.ALIAS_LISTS_PATH).GET()));
            assertEquals(List.of(southwest.id(), cleveland.id()), longValues(
                aliasList(aliasListCatalog, county.id()).at("/unmatched_talkgroup_policy/scan_list_ids")));
            assertEquals(List.of(cleveland.id()), longValues(
                aliasList(aliasListCatalog, city.id()).at("/unmatched_talkgroup_policy/scan_list_ids")));

            JsonNode southwestDetail = data(send(client, request(origin,
                AliasAdminHttpController.SCAN_LISTS_PATH + "/" + southwest.id()).GET()));
            assertEquals("SouthWest", southwestDetail.at("/scan_list/name").textValue());
            assertEquals(List.of(southwestDispatch.id(), mutualAid.id()),
                OBJECT_MAPPER.convertValue(southwestDetail.get("alias_ids"),
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, Long.class)));
            assertEquals(List.of(county.id()), longValues(southwestDetail.get("unmatched_alias_list_ids")));
            JsonNode clevelandDetail = data(send(client, request(origin,
                AliasAdminHttpController.SCAN_LISTS_PATH + "/" + cleveland.id()).GET()));
            assertEquals("Cleveland", clevelandDetail.at("/scan_list/name").textValue());
            assertEquals(List.of(clevelandDispatch.id(), mutualAid.id()),
                OBJECT_MAPPER.convertValue(clevelandDetail.get("alias_ids"),
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, Long.class)));
            assertEquals(List.of(county.id(), city.id()),
                longValues(clevelandDetail.get("unmatched_alias_list_ids")));
            assertEquals(cityUnmatched.revision(), clevelandDetail.get("revision").longValue());

            ScanListConfiguration reloaded = new ScanListDatabaseStore(database).loadConfiguration();
            assertEquals(southwest.id(), reloaded.scanList("SouthWest").getId());
            assertEquals(cleveland.id(), reloaded.scanList("Cleveland").getId());
            assertEquals(Set.of(southwest.id()),
                reloaded.scanListIdsForAlias(southwestDispatch.id()));
            assertEquals(Set.of(cleveland.id()),
                reloaded.scanListIdsForAlias(clevelandDispatch.id()));
            assertEquals(Set.of(southwest.id(), cleveland.id()),
                reloaded.scanListIdsForAlias(mutualAid.id()));
            assertEquals(Set.of(southwest.id(), cleveland.id()),
                reloaded.scanListIdsForUnmatchedTalkgroups(county.id()));
            assertEquals(Set.of(cleveland.id()),
                reloaded.scanListIdsForUnmatchedTalkgroups(city.id()));

            AliasDatabaseStore aliasStore = new AliasDatabaseStore(database);
            List<AliasListDefinition> definitions = aliasStore.loadAliasListDefinitions();
            Map<Long,Alias> persistedAliases = aliasStore.loadAliases(definitions).stream()
                .collect(Collectors.toMap(Alias::getId, Function.identity()));
            assertEquals(county.id(), persistedAliases.get(southwestDispatch.id()).getAliasListId());
            assertEquals(city.id(), persistedAliases.get(clevelandDispatch.id()).getAliasListId());
            assertEquals(city.id(), persistedAliases.get(mutualAid.id()).getAliasListId());

            ScanListModel reloadedModel = new ScanListModel(null);
            reloadedModel.replaceConfiguration(reloaded);
            verifyCompletedCallDelivery(reloadedModel, southwest, cleveland,
                manager.getAliasModel().getAliasList(county.name()), manager.getAliasModel().getAliasList(city.name()));
        }
        finally
        {
            server.stop(0);
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    private static void verifyCompletedCallDelivery(ScanListModel model, Resource southwest,
                                                     Resource cleveland, AliasList county, AliasList city)
        throws Exception
    {
        StatsWebCallService service = new StatsWebCallService(model, WebCallConfiguration.defaults());
        service.start();

        try
        {
            try(StatsLiveEventHub.Subscription southwestListener = service.subscribe(Set.of(southwest.id()));
                StatsLiveEventHub.Subscription clevelandListener = service.subscribe(Set.of(cleveland.id())))
            {
                service.receive(completedCall(1, county, 1101));
                assertEquals(List.of(southwest.id()), scanListIds(awaitCall(southwestListener)));
                assertNull(clevelandListener.poll(200, TimeUnit.MILLISECONDS));

                service.receive(completedCall(2, county, 1199));
                assertEquals(List.of(southwest.id(), cleveland.id()), scanListIds(awaitCall(southwestListener)));
                assertEquals(List.of(southwest.id(), cleveland.id()), scanListIds(awaitCall(clevelandListener)));

                service.receive(completedCall(3, city, 2202));
                assertEquals(List.of(cleveland.id()), scanListIds(awaitCall(clevelandListener)));
                assertNull(southwestListener.poll(200, TimeUnit.MILLISECONDS));

                service.receive(completedCall(4, city, 2299));
                assertEquals(List.of(cleveland.id()), scanListIds(awaitCall(clevelandListener)));
                assertNull(southwestListener.poll(200, TimeUnit.MILLISECONDS));
            }

            try(StatsLiveEventHub.Subscription both = service.subscribe(Set.of(southwest.id(), cleveland.id())))
            {
                service.receive(completedCall(5, city, 3303));
                assertEquals(List.of(southwest.id(), cleveland.id()), scanListIds(awaitCall(both)));
                assertNull(both.poll(200, TimeUnit.MILLISECONDS),
                    "One completed call must not be duplicated when two selected scan lists overlap");

                service.receive(completedCall(6, county, 1199));
                assertEquals(List.of(southwest.id(), cleveland.id()), scanListIds(awaitCall(both)));
                assertNull(both.poll(200, TimeUnit.MILLISECONDS),
                    "One unmatched call must not be duplicated when two global routes overlap");
            }

            assertEquals(6L, awaitPublishedCalls(service, 6L));
        }
        finally
        {
            service.close();
        }
    }

    private static CompletedAudioCall completedCall(long sequence, AliasList aliasList, int talkgroup)
    {
        long startedAt = 1_000L * sequence;
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            SystemConfigurationIdentifier.create("Test System"), APCO25Talkgroup.create(talkgroup)));
        AudioCallSnapshot snapshot = new AudioCallSnapshot(new AudioCallId(77, sequence, 0), null, aliasList,
            identifiers, Set.of(), startedAt, startedAt + 100L, 1, sequence, startedAt, startedAt + 100L,
            false, true, false, false, false);
        return new CompletedAudioCall(snapshot, List.of(new float[800]));
    }

    private static StatsLiveEventHub.LiveEvent awaitCall(StatsLiveEventHub.Subscription subscription)
        throws InterruptedException
    {
        StatsLiveEventHub.LiveEvent event = subscription.poll(5, TimeUnit.SECONDS);
        assertNotNull(event);
        assertEquals("call", event.name());
        return event;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> scanListIds(StatsLiveEventHub.LiveEvent event)
    {
        return (List<Long>)((Map<String,Object>)event.data()).get("scan_list_ids");
    }

    private static long awaitPublishedCalls(StatsWebCallService service, long expected) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long actual;

        do
        {
            actual = ((Number)service.status().get("published_calls")).longValue();
            if(actual == expected)
            {
                return actual;
            }
            Thread.sleep(10);
        }
        while(System.nanoTime() < deadline);

        return actual;
    }

    private static Resource createAliasList(HttpClient client, URI origin, long revision, String name)
        throws Exception
    {
        JsonNode result = data(send(client, jsonRequest(origin, AliasAdminHttpController.ALIAS_LISTS_PATH)
            .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                "revision", revision, "name", name, "family", "p25"))))));
        return new Resource(result.get("alias_list_id").longValue(), result.get("revision").longValue(), name);
    }

    private static Resource createScanList(HttpClient client, URI origin, long revision, String name, int sortOrder)
        throws Exception
    {
        JsonNode result = data(send(client, jsonRequest(origin, AliasAdminHttpController.SCAN_LISTS_PATH)
            .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                "revision", revision, "scan_list", Map.of("sort_order", sortOrder, "name", name,
                    "description", name + " calls", "published", true, "default", false)))))));
        return new Resource(result.get("scan_list_id").longValue(), result.get("revision").longValue(), name);
    }

    private static Resource createAlias(HttpClient client, URI origin, long revision, long aliasListId,
                                        String name, int talkgroup, List<Long> scanListIds) throws Exception
    {
        Map<String,Object> alias = alias(aliasListId, name, talkgroup);
        alias.put("scan_list_ids", scanListIds);
        JsonNode result = data(send(client, jsonRequest(origin, AliasAdminHttpController.ALIASES_PATH)
            .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                "revision", revision, "alias", alias))))));
        return new Resource(result.at("/alias_ids/0").longValue(), result.get("revision").longValue(), name);
    }

    private static Resource updateUnmatchedPolicy(HttpClient client, URI origin, long revision, long aliasListId,
                                                   List<Long> scanListIds) throws Exception
    {
        JsonNode result = data(send(client, jsonRequest(origin, AliasAdminHttpController.ALIAS_LISTS_PATH + "/" +
            aliasListId + "/unmatched-talkgroups").PUT(HttpRequest.BodyPublishers.ofString(
            OBJECT_MAPPER.writeValueAsString(Map.of("revision", revision, "recordable", false,
                "broadcast_channels", List.of(), "scan_list_ids", scanListIds))))));
        return new Resource(aliasListId, result.get("revision").longValue(), null);
    }

    private static JsonNode aliasList(JsonNode catalog, long aliasListId)
    {
        for(JsonNode aliasList : catalog.path("alias_lists"))
        {
            if(aliasList.path("alias_list_id").longValue() == aliasListId)
            {
                return aliasList;
            }
        }

        throw new AssertionError("Alias List [" + aliasListId + "] was not returned");
    }

    private static List<Long> longValues(JsonNode values)
    {
        List<Long> result = new java.util.ArrayList<>();
        values.forEach(value -> result.add(value.longValue()));
        return List.copyOf(result);
    }

    private static Map<String,Object> alias(long aliasListId, String name, int talkgroup)
    {
        Map<String,Object> alias = new LinkedHashMap<>();
        alias.put("alias_list_id", aliasListId);
        alias.put("name", name);
        alias.put("description", null);
        alias.put("group", "Dispatch");
        alias.put("color", 0);
        alias.put("icon_name", null);
        alias.put("recordable", false);
        alias.put("broadcast_channels", List.of());
        alias.put("stream_as_talkgroup", null);
        alias.put("matcher", Map.of("type", "talkgroup", "protocol", "p25", "variant", "phase_2",
            "value", talkgroup));
        return alias;
    }

    private static HttpRequest.Builder jsonRequest(URI origin, String path)
    {
        return request(origin, path).header("Content-Type", "application/json");
    }

    private static HttpRequest.Builder request(URI origin, String path)
    {
        return HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(30));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception
    {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception
    {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return OBJECT_MAPPER.readTree(response.body()).get("data");
    }

    private record Resource(long id, long revision, String name) {}

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
