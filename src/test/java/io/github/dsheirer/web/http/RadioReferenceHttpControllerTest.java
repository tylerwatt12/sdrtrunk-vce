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
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService;
import io.github.dsheirer.preference.radioreference.RadioReferencePreference.PreferredAliasList;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway;
import io.github.dsheirer.service.radioreference.RadioReferenceGatewayException;
import io.github.dsheirer.service.radioreference.RadioReferenceImportService;
import io.github.dsheirer.service.radioreference.RadioReferenceImportServiceTestSupport;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Account;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Agency;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Country;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.CountryDirectory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.County;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.CountyDirectory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.ConventionalFrequency;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.FrequencyCategory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.FrequencyResult;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Mode;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RemoteTalkgroup;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RemoteTalkgroupCategory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Site;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.SiteChannel;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.State;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.StateDirectory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteChannel;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteDetails;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSystem;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSystemDetails;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadioReferenceHttpControllerTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void previewsAndAppliesOnlyVerifiedP25NamesThroughStrictEnrichmentRoutes() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("enrichment-http-data");
        SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null);
        manager.init();
        var channels = ChannelAdministrationServiceTestSupport.create(manager);
        long aliasListId = manager.getAliasModel().aliasListDefinitions().stream()
            .filter(list -> list.getFamily() == AliasListFamily.P25).findFirst().orElseThrow().getId();
        ChannelDefinition template = channels.template("p25-phase1");
        String id = channels.create(new ChannelDefinition(null, "p25-phase1", "Old system", "Old site", "My channel",
            null, aliasListId, new ChannelDefinition.Source(List.of(853_162_500L), null, null, null, null, null),
            template.settings(), template.frequencyMap(), template.eventLogs(), template.recorders(),
            template.auxiliaryDecoders(), template.observed())).configurationIds().getFirst();
        manager.getChannelModel().getChannels().stream().filter(channel -> channel.getConfigurationId().equals(id))
            .findFirst().orElseThrow().setP25SiteIdentity(new P25SiteIdentity(0xBEE00, 0x123, 12, 12));
        FakeSettings settings = new FakeSettings();
        settings.storeLocation(1, 10, 100);
        FakeGateway gateway = new FakeGateway();
        try(RadioReferenceDirectoryService service = new RadioReferenceDirectoryService((user, password) -> gateway))
        {
            RadioReferenceHttpController controller = new RadioReferenceHttpController(service, settings,
                RadioReferenceImportServiceTestSupport.create(service, manager, channels));
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(RadioReferenceHttpController.PATH, controller::handle);
            server.start();
            try
            {
                URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                String previewBody = OBJECT_MAPPER.writeValueAsString(Map.of("configuration_id", id,
                    "revision", channels.currentRevision()));
                assertEquals(401, send(client, jsonRequest(origin, "/channels/enrichment/preview")
                    .POST(HttpRequest.BodyPublishers.ofString(previewBody))).statusCode());
                data(send(client, jsonRequest(origin, "/session").PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"user_name\":\"test-user\",\"password\":\"test-password\",\"remember\":false}"))));
                assertEquals(400, send(client, jsonRequest(origin, "/channels/enrichment/preview")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"configuration_id\":\"" + id +
                        "\",\"revision\":" + channels.currentRevision() + ",\"system\":\"Forged\"}"))).statusCode());
                JsonNode preview = data(send(client, jsonRequest(origin, "/channels/enrichment/preview")
                    .POST(HttpRequest.BodyPublishers.ofString(previewBody))));
                assertEquals("matched", preview.at("/state").textValue());
                assertEquals("Old system", preview.at("/changes/0/before").textValue());
                assertEquals("State P25", preview.at("/changes/0/after").textValue());
                assertEquals("https://www.radioreference.com/db/sid/2001", preview.at("/system_url").textValue());
                String applyBody = OBJECT_MAPPER.writeValueAsString(Map.of("preview_id",
                    preview.at("/preview_id").textValue(), "replace_name", false));
                assertEquals(400, send(client, jsonRequest(origin, "/channels/enrichment/apply")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"preview_id\":\"" +
                        preview.at("/preview_id").textValue() + "\"}"))).statusCode());
                JsonNode saved = data(send(client, jsonRequest(origin, "/channels/enrichment/apply")
                    .POST(HttpRequest.BodyPublishers.ofString(applyBody))));
                assertEquals(id, saved.at("/configuration_id").textValue());
                assertEquals("State P25", channels.get(id).channel().system());
                assertEquals("My channel", channels.get(id).channel().name());
                assertEquals(409, send(client, jsonRequest(origin, "/channels/enrichment/apply")
                    .POST(HttpRequest.BodyPublishers.ofString(applyBody))).statusCode());
            }
            finally { server.stop(0); }
        }
        finally
        {
            manager.flushConfiguration();
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void draftNamingRouteRequiresAnAuthoritativeRecordAndReturnsNamesWithoutCreatingAChannel() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("draft-naming-http-data");
        SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null, new AliasModel(), null);
        manager.init();
        var channels = ChannelAdministrationServiceTestSupport.create(manager);
        FakeSettings settings = new FakeSettings();
        settings.storeLocation(1, 10, 100);
        FakeGateway gateway = new FakeGateway();
        gateway.namingFrequencyResults = List.of(new FrequencyResult(155.25, 0, "WXYZ", "County Dispatch", "Dispatch",
            "123.0 PL", "", "", "", "FMN", "", List.of(), 501, 0, 0, 100));
        try(RadioReferenceDirectoryService service = new RadioReferenceDirectoryService((user, password) -> gateway))
        {
            RadioReferenceHttpController controller = new RadioReferenceHttpController(service, settings,
                RadioReferenceImportServiceTestSupport.create(service, manager, channels));
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(RadioReferenceHttpController.PATH, controller::handle);
            server.start();
            try
            {
                URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                data(send(client, jsonRequest(origin, "/session").PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"user_name\":\"test-user\",\"password\":\"test-password\",\"remember\":false}"))));
                Map<String,Object> draft = Map.of("protocol_id", "nbfm", "system", "My system", "site", "My site",
                    "name", "My channel", "source", Map.of("frequencies_hz", List.of(155_250_000L)),
                    "settings", Map.of("bandwidth", "BW_12_5", "ctcss_frequency_hz", 0));
                String body = OBJECT_MAPPER.writeValueAsString(Map.of("channel", draft));
                JsonNode choices = data(send(client, jsonRequest(origin, "/channels/enrichment/preview")
                    .POST(HttpRequest.BodyPublishers.ofString(body))));
                assertEquals("choose_record", choices.path("state").asText());
                assertEquals(1, choices.path("candidates").size());
                assertTrue(choices.at("/candidates/0/context").asText().contains("Dispatch"));
                String selectedBody = OBJECT_MAPPER.writeValueAsString(Map.of("channel", draft,
                    "lookup_id", choices.path("lookup_id").asText(), "candidate_id",
                    choices.at("/candidates/0/candidate_id").asText()));
                JsonNode selected = data(send(client, jsonRequest(origin, "/channels/enrichment/preview")
                    .POST(HttpRequest.BodyPublishers.ofString(selectedBody))));
                assertEquals("matched", selected.path("state").asText());
                assertEquals("Franklin", selected.at("/names/system").asText());
                assertEquals("Dispatch", selected.at("/names/site").asText());
                assertEquals("County Dispatch", selected.at("/names/name").asText());
                assertTrue(channels.catalog().channels().isEmpty());
                assertEquals(400, send(client, jsonRequest(origin, "/channels/enrichment/preview")
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of(
                        "channel", draft, "lookup_id", choices.path("lookup_id").asText(),
                        "candidate_id", "forged", "name", "Forged name"))))).statusCode());
                gateway.namingFrequencyResults = List.of();
                long unchangedRevision = channels.currentRevision();
                Map<String,Object> weatherDraft = Map.of("protocol_id", "nbfm", "system", "NOAA", "site", "WX",
                    "name", "WX5", "source", Map.of("frequencies_hz", List.of(162_500_000L)), "settings", Map.of());
                HttpResponse<String> noMatchResponse = send(client, jsonRequest(origin, "/channels/enrichment/preview")
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(
                        Map.of("channel", weatherDraft, "state_id", 10)))));
                assertEquals(200, noMatchResponse.statusCode());
                JsonNode noMatch = data(noMatchResponse);
                assertEquals("no_match", noMatch.path("state").asText());
                assertEquals("No RadioReference record matches this frequency and radio type in the selected region.",
                    noMatch.path("reason").asText());
                assertFalse(noMatch.hasNonNull("preview_id"));
                assertTrue(channels.catalog().channels().isEmpty());
                assertEquals(unchangedRevision, channels.currentRevision());
                // A remembered list is independent of bookmarking and is a suggestion on both response stages.
                settings.savePreferredAliasList(2001, 12L);
                gateway.namingFrequencyResults = null;
                Map<String,Object> trunkedDraft = Map.of("protocol_id", "p25-phase1", "name", "Draft",
                    "source", Map.of("frequencies_hz", List.of(853_162_500L)), "settings", Map.of("modulation", "C4FM"));
                JsonNode trunked = data(send(client, jsonRequest(origin, "/channels/enrichment/preview")
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of("channel", trunkedDraft))))));
                assertEquals(12, trunked.at("/candidates/0/preferred_alias_list_id").asInt());
                JsonNode trunkedSelected = data(send(client, jsonRequest(origin, "/channels/enrichment/preview")
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(Map.of("channel", trunkedDraft,
                        "lookup_id", trunked.path("lookup_id").asText(), "candidate_id",
                        trunked.at("/candidates/0/candidate_id").asText()))))));
                assertEquals(12, trunkedSelected.path("preferred_alias_list_id").asInt());
                assertEquals("State P25 · Franklin Simulcast", trunkedSelected.at("/names/name").asText());
            }
            finally { server.stop(0); }
        }
        finally
        {
            manager.flushConfiguration();
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void catalogRoutesKeepReviewedTalkgroupsAndForwardSourceFreshnessAndRefreshToTheGateway() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("catalog-http-data");
        SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null);
        manager.init();
        FakeGateway gateway = new FakeGateway();

        try(RadioReferenceDirectoryService service = new RadioReferenceDirectoryService((user, password) -> {
            if("invalid-user".equals(user))
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INVALID_CREDENTIALS);
            return gateway;
        }))
        {
            RadioReferenceImportService importer = new RadioReferenceImportService(service, manager);
            RadioReferenceHttpController controller = new RadioReferenceHttpController(service, new FakeSettings(),
                importer);
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(RadioReferenceHttpController.PATH, controller::handle);
            server.start();

            try
            {
                URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                String loginBody = "{\"user_name\":\"test-user\",\"password\":\"catalog-test-password\",\"remember\":false}";
                String availabilityPath = "/systems/talkgroups/availability?system_id=2001";
                assertEquals(401, send(client, request(origin, availabilityPath).GET()).statusCode());
                assertEquals("VALID_PREMIUM", data(send(client, jsonRequest(origin, "/session")
                    .PUT(HttpRequest.BodyPublishers.ofString(loginBody)))).at("/account/state").textValue());

                JsonNode available = data(send(client, request(origin, availabilityPath).GET()));
                assertTrue(available.path("has_talkgroups").asBoolean());
                assertEquals(1, available.size(), "availability should return only its boolean result");
                assertTrue(data(send(client, request(origin, availabilityPath).GET()))
                    .path("has_talkgroups").asBoolean());
                assertEquals(1, gateway.talkgroupReads.get(2001).get());
                assertTrue(gateway.systemReads.isEmpty(), "availability must not load system details");
                assertTrue(gateway.categoryReads.isEmpty(), "availability must not load categories");
                assertEquals(400, send(client,
                    request(origin, availabilityPath + "&limit=1").GET()).statusCode());
                assertEquals(400, send(client,
                    request(origin, "/systems/talkgroups/availability?system_id=0").GET()).statusCode());
                gateway.emptyTalkgroups = true;
                assertFalse(data(send(client,
                    request(origin, "/systems/talkgroups/availability?system_id=2003").GET()))
                    .path("has_talkgroups").asBoolean());
                gateway.emptyTalkgroups = false;

                String talkgroupsPath = "/systems/talkgroups/catalog?system_id=2001";
                JsonNode first = data(send(client, request(origin, talkgroupsPath).GET()));
                String firstCatalogId = first.at("/catalog_id").textValue();
                assertEquals("Dispatch", first.at("/items/0/talkgroup/alpha_tag").textValue());
                assertEquals(firstCatalogId, data(send(client, request(origin, talkgroupsPath).GET()))
                    .at("/catalog_id").textValue());
                assertEquals(firstCatalogId, data(send(client,
                    request(origin, talkgroupsPath + "&catalog_id=" + firstCatalogId).GET()))
                    .at("/catalog_id").textValue());
                data(send(client, request(origin, "/systems/talkgroups/catalog?system_id=2002").GET()));
                assertEquals(1, gateway.talkgroupReads.get(2001).get());
                assertEquals(1, gateway.talkgroupReads.get(2002).get());

                gateway.alphaTag = "Updated dispatch";
                assertEquals("Dispatch", data(send(client, request(origin, talkgroupsPath).GET()))
                    .at("/items/0/talkgroup/alpha_tag").textValue());
                JsonNode refreshed = data(send(client, request(origin, talkgroupsPath + "&refresh=true").GET()));
                String refreshedCatalogId = refreshed.at("/catalog_id").textValue();
                assertFalse(firstCatalogId.equals(refreshedCatalogId));
                assertEquals("Updated dispatch", refreshed.at("/items/0/talkgroup/alpha_tag").textValue());
                assertEquals(2, gateway.talkgroupReads.get(2001).get());
                assertEquals(refreshedCatalogId, data(send(client, request(origin, talkgroupsPath).GET()))
                    .at("/catalog_id").textValue());

                for(String path: List.of("/systems/details", "/systems/sites/catalog"))
                {
                    for(int systemId: List.of(2001, 2002))
                    {
                        data(send(client, request(origin, path + "?system_id=" + systemId).GET()));
                        data(send(client, request(origin, path + "?system_id=" + systemId).GET()));
                    }
                    data(send(client, request(origin, path + "?system_id=2001&refresh=true").GET()));
                }
                assertEquals(5, gateway.systemReads.get(2001).get());
                assertEquals(3, gateway.systemReads.get(2002).get());
                assertEquals(3, gateway.siteReads.get(2001).get());
                assertEquals(2, gateway.siteReads.get(2002).get());
                assertEquals(1, gateway.systemRefreshes.get(), "explicit system refresh must reach the shared source");
                assertEquals(1, gateway.siteRefreshes.get(), "explicit site refresh must reach the shared source");

                for(String path: List.of("/systems/talkgroups/catalog", "/systems/details", "/systems/sites/catalog"))
                {
                    assertEquals(400, send(client,
                        request(origin, path + "?system_id=2001&refresh=invalid").GET()).statusCode(), path);
                }
                assertEquals(2, gateway.talkgroupReads.get(2001).get());

                HttpResponse<String> rejectedLogin = send(client, jsonRequest(origin, "/session")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"user_name\":\"invalid-user\",\"password\":\"invalid-password\",\"remember\":false}")));
                assertEquals("INVALID_CREDENTIALS", data(rejectedLogin).at("/account/state").textValue());
                for(String path: List.of(availabilityPath, talkgroupsPath,
                    talkgroupsPath + "&catalog_id=" + refreshedCatalogId,
                    "/systems/details?system_id=2001", "/systems/sites/catalog?system_id=2001"))
                {
                    HttpResponse<String> denied = send(client, request(origin, path).GET());
                    assertEquals(401, denied.statusCode(), denied.body());
                    assertEquals("not_authenticated", OBJECT_MAPPER.readTree(denied.body())
                        .at("/error/code").textValue(), denied.body());
                }
                assertEquals(2, gateway.talkgroupReads.get(2001).get(),
                    "failed replacement login must not serve cached data or contact the previous gateway");

                data(send(client, jsonRequest(origin, "/session").PUT(HttpRequest.BodyPublishers.ofString(loginBody))));
                assertEquals(400, send(client,
                    request(origin, talkgroupsPath + "&catalog_id=" + refreshedCatalogId).GET()).statusCode(),
                    "the previous account catalog must remain discarded after a new successful login");
                assertTrue(data(send(client, request(origin, availabilityPath).GET()))
                    .path("has_talkgroups").asBoolean());
                JsonNode reloaded = data(send(client, request(origin, talkgroupsPath).GET()));
                assertFalse(refreshedCatalogId.equals(reloaded.at("/catalog_id").textValue()));
                assertEquals(3, gateway.talkgroupReads.get(2001).get());
            }
            finally
            {
                server.stop(0);
            }
        }
        finally
        {
            try { manager.flushConfiguration(); }
            finally { MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager()); }
        }
    }

    @Test
    void managesPremiumSessionLocationAndExactFrequencyResultsWithoutExposingPassword() throws Exception
    {
        FakeSettings settings = new FakeSettings();
        FakeGateway gateway = new FakeGateway();

        try(RadioReferenceDirectoryService service = new RadioReferenceDirectoryService((user, password) -> gateway))
        {
            RadioReferenceHttpController controller = new RadioReferenceHttpController(service, settings);
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(RadioReferenceHttpController.PATH, controller::handle);
            server.start();

            try
            {
                URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

                JsonNode initial = data(send(client, request(origin, "").GET()));
                assertEquals("SIGNED_OUT", initial.at("/account/state").textValue());
                assertFalse(initial.at("/credentials_stored").booleanValue());

                HttpResponse<String> unauthenticated = send(client,
                    request(origin, "/frequencies?state_id=10&frequency_hz=853162500").GET());
                assertEquals(401, unauthenticated.statusCode());

                String loginBody = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "user_name", "test-user", "password", "secret-value", "remember", true));
                HttpResponse<String> login = send(client, jsonRequest(origin, "/session")
                    .PUT(HttpRequest.BodyPublishers.ofString(loginBody)));
                assertEquals("VALID_PREMIUM", data(login).at("/account/state").textValue(), login.body());
                assertFalse(login.body().contains("secret-value"));
                assertTrue(settings.credentialsStored);

                assertEquals("United States", data(send(client, request(origin, "/countries").GET()))
                    .at("/items/0/name").textValue());
                assertEquals("Test State", data(send(client,
                    request(origin, "/states?country_id=1").GET())).at("/items/0/name").textValue());

                HttpResponse<String> location = send(client, jsonRequest(origin, "/location")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"country_id\":1,\"state_id\":10,\"county_id\":100}")));
                assertEquals(1, data(location).at("/country_id").intValue());
                assertEquals(10, settings.stateId);
                assertEquals(100, settings.countyId);

                JsonNode matches = data(send(client,
                    request(origin, "/frequencies?state_id=10&frequency_hz=853162500").GET()));
                assertEquals(1, matches.at("/total_items").intValue());
                assertEquals("State P25 Site 012 Franklin Simulcast",
                    matches.at("/items/0/description").textValue());
                assertEquals(853.1625, matches.at("/items/0/output_mhz").doubleValue());
                assertEquals(2001, matches.at("/items/0/system_id").intValue());
                assertEquals("Mode 4", matches.at("/items/0/mode_name").textValue());
                assertEquals("Trunked", matches.at("/items/0/channel_use").textValue());
                assertEquals("Franklin Simulcast", matches.at("/items/0/site_name").textValue());
                assertEquals(12, matches.at("/items/0/site_number").intValue());
                assertEquals("Franklin", matches.at("/items/0/county_name").textValue());
                assertEquals("https://www.radioreference.com/db/sid/2001",
                    matches.at("/items/0/radio_reference_url").textValue());

                JsonNode details = data(send(client, request(origin,
                    "/frequencies/details?frequency_hz=853162500&system_id=2001&site_number=12&sub_category_id=0" +
                        "&agency_id=0&county_id=100&mode=4").GET()));
                assertEquals("Control", details.at("/site/channel_use").textValue());
                assertEquals("Franklin Simulcast", details.at("/site/site_name").textValue());
                assertEquals("https://www.radioreference.com/db/site/3001",
                    details.at("/site/radio_reference_url").textValue());

                assertEquals("Franklin", data(send(client,
                    request(origin, "/counties?state_id=10").GET())).at("/items/0/name").textValue());
                assertEquals("State P25", data(send(client,
                    request(origin, "/browse?country_id=1&state_id=10").GET())).at("/items/0/name").textValue());
                assertEquals("State P25", data(send(client,
                    request(origin, "/browse/catalog?country_id=1&state_id=10").GET())).at("/0/name").textValue());
                assertEquals(1_700_000_000_000L, data(send(client,
                    request(origin, "/browse/catalog?country_id=1&state_id=10").GET()))
                    .at("/0/last_updated_epoch_millis").longValue());
                assertEquals("Project 25", data(send(client,
                    request(origin, "/systems/details?system_id=2001").GET())).at("/type").textValue());
                assertEquals("Franklin Simulcast", data(send(client,
                    request(origin, "/systems/sites?system_id=2001").GET())).at("/items/0/name").textValue());
                assertEquals("Franklin Simulcast", data(send(client,
                    request(origin, "/systems/sites/catalog?system_id=2001").GET())).at("/0/name").textValue());
                assertEquals(1201, data(send(client,
                    request(origin, "/systems/talkgroups?system_id=2001").GET()))
                    .at("/items/0/value").intValue());
                assertEquals("Dispatch", data(send(client,
                    request(origin, "/conventional/categories?owner_kind=COUNTY&owner_id=100").GET()))
                    .at("/items/0/sub_category_name").textValue());
                assertEquals(155_250_000L, data(send(client,
                    request(origin, "/conventional/frequencies?sub_category_id=501").GET()))
                    .at("/items/0/downlink_hz").longValue());
                JsonNode conventional = data(send(client, request(origin,
                    "/conventional/frequencies?owner_kind=COUNTY&owner_id=100&sub_category_id=501").GET()));
                assertEquals("Franklin", conventional.at("/names/8001/system").textValue());
                assertEquals("Dispatch", conventional.at("/names/8001/site").textValue());
                assertEquals("County Dispatch", conventional.at("/names/8001/name").textValue());

                String invalidConventionalPreview = "{" +
                    "\"owner_kind\":\"COUNTY\",\"owner_id\":100,\"sub_category_id\":501," +
                    "\"frequency_id\":7101,\"alias_list_id\":0}";
                HttpResponse<String> rejectedAliasList = send(client,
                    jsonRequest(origin, "/imports/conventional/preview")
                        .POST(HttpRequest.BodyPublishers.ofString(invalidConventionalPreview)));
                assertEquals(400, rejectedAliasList.statusCode(), rejectedAliasList.body());
                assertTrue(rejectedAliasList.body().contains("alias_list_id must be a positive integer"),
                    rejectedAliasList.body());

                String bookmarkBody = "{\"kind\":\"TRUNKED_SYSTEM\",\"id\":2001," +
                    "\"parent_id\":0,\"owner_kind\":\"\",\"name\":\"State P25\"," +
                    "\"parent_name\":\"\",\"preferred_alias_list_id\":12}";
                assertEquals("TRUNKED_SYSTEM", data(send(client, jsonRequest(origin, "/bookmarks")
                    .PUT(HttpRequest.BodyPublishers.ofString(bookmarkBody)))).at("/0/kind").textValue());
                assertEquals(2001, data(send(client, request(origin, "/bookmarks").GET()))
                    .at("/0/id").intValue());
                assertEquals(12, data(send(client, request(origin, "/bookmarks").GET()))
                    .at("/0/preferred_alias_list_id").intValue());
                assertEquals(0, data(send(client, request(origin, "/system-preferences").GET()))
                    .at("/items").size());
                String preferredBody = "{\"system_id\":2001,\"preferred_alias_list_id\":12}";
                assertEquals(12, data(send(client, jsonRequest(origin, "/system-preferences")
                    .PUT(HttpRequest.BodyPublishers.ofString(preferredBody))))
                    .at("/preferred_alias_list_id").longValue());
                assertEquals(2001, data(send(client, request(origin, "/system-preferences").GET()))
                    .at("/items/0/system_id").intValue());
                assertEquals(0, data(send(client, jsonRequest(origin, "/bookmarks")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(bookmarkBody)))).size());
                assertEquals(12, data(send(client, request(origin, "/system-preferences").GET()))
                    .at("/items/0/preferred_alias_list_id").longValue());
                assertEquals(400, send(client, jsonRequest(origin, "/system-preferences")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(
                        "{\"system_id\":0}"))).statusCode());
                assertTrue(data(send(client, jsonRequest(origin, "/system-preferences")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(
                        "{\"system_id\":2001}")))).at("/preferred_alias_list_id").isNull());
                assertTrue(data(send(client, request(origin, "/system-preferences").GET()))
                    .at("/items/0/preferred_alias_list_id").isNull());
                assertEquals(400, send(client, jsonRequest(origin, "/system-preferences")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"system_id\":2001,\"preferred_alias_list_id\":0}"))).statusCode());
                assertEquals(400, send(client, jsonRequest(origin, "/system-preferences")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"system_id\":2001,\"preferred_alias_list_id\":999}"))).statusCode());
                assertTrue(data(send(client, request(origin, "/system-preferences").GET()))
                    .at("/items/0/preferred_alias_list_id").isNull());

                for(String removedImportPath: List.of(
                    "/systems/site-preview?system_id=2001&site_id=3001",
                    "/systems/channels",
                    "/systems/talkgroups/import",
                    "/conventional/channels"))
                {
                    HttpResponse<String> removedImport = send(client, request(origin, removedImportPath).GET());
                    assertEquals(404, removedImport.statusCode(), removedImportPath);
                }

                HttpResponse<String> logout = send(client, request(origin, "/session").DELETE());
                assertEquals("SIGNED_OUT", data(logout).at("/account/state").textValue());
                assertFalse(settings.credentialsStored);
                assertFalse(logout.body().contains("secret-value"));
            }
            finally
            {
                server.stop(0);
            }
        }
    }

    private static HttpRequest.Builder request(URI origin, String suffix)
    {
        return HttpRequest.newBuilder(origin.resolve(RadioReferenceHttpController.PATH + suffix))
            .timeout(Duration.ofSeconds(10));
    }

    private static HttpRequest.Builder jsonRequest(URI origin, String suffix)
    {
        return request(origin, suffix).header("Content-Type", "application/json");
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

    private static final class FakeSettings implements RadioReferenceHttpController.Settings
    {
        private final java.util.Map<String,io.github.dsheirer.preference.radioreference.RadioReferencePreference.Bookmark>
            bookmarks = new java.util.LinkedHashMap<>();
        private final java.util.Map<Integer,PreferredAliasList> preferredAliasLists = new java.util.LinkedHashMap<>();
        private boolean credentialsStored;
        private String userName;
        private String password;
        private int countryId = -1;
        private int stateId = -1;
        private int countyId = -1;

        @Override
        public boolean hasStoredCredentials()
        {
            return credentialsStored;
        }

        @Override
        public String userName()
        {
            return userName;
        }

        @Override
        public String password()
        {
            return password;
        }

        @Override
        public int countryId()
        {
            return countryId;
        }

        @Override
        public int stateId()
        {
            return stateId;
        }

        @Override
        public int countyId()
        {
            return countyId;
        }

        @Override
        public void storeCredentials(String userName, String password)
        {
            credentialsStored = true;
            this.userName = userName;
            this.password = password;
        }

        @Override
        public void clearCredentials()
        {
            credentialsStored = false;
            userName = null;
            password = null;
        }

        @Override
        public void storeLocation(int countryId, int stateId, Integer countyId)
        {
            this.countryId = countryId;
            this.stateId = stateId;
            this.countyId = countyId != null ? countyId : -1;
        }

        @Override
        public List<io.github.dsheirer.preference.radioreference.RadioReferencePreference.Bookmark> bookmarks()
        {
            return List.copyOf(bookmarks.values());
        }

        @Override
        public List<io.github.dsheirer.preference.radioreference.RadioReferencePreference.Bookmark> saveBookmark(
            io.github.dsheirer.preference.radioreference.RadioReferencePreference.Bookmark bookmark)
        {
            bookmarks.put(bookmark.key(), bookmark);
            return bookmarks();
        }

        @Override
        public List<io.github.dsheirer.preference.radioreference.RadioReferencePreference.Bookmark> removeBookmark(
            io.github.dsheirer.preference.radioreference.RadioReferencePreference.Bookmark bookmark)
        {
            bookmarks.remove(bookmark.key());
            return bookmarks();
        }

        @Override
        public List<PreferredAliasList> preferredAliasLists()
        {
            return List.copyOf(preferredAliasLists.values());
        }

        @Override
        public PreferredAliasList savePreferredAliasList(int systemId, long aliasListId)
        {
            PreferredAliasList choice = new PreferredAliasList(systemId, aliasListId);
            preferredAliasLists.put(systemId, choice);
            return choice;
        }

        @Override
        public PreferredAliasList clearPreferredAliasList(int systemId)
        {
            PreferredAliasList cleared = new PreferredAliasList(systemId, null);
            preferredAliasLists.put(systemId, cleared);
            return cleared;
        }

        @Override
        public boolean aliasListExists(long aliasListId)
        {
            return aliasListId == 12;
        }
    }

    private static final class FakeGateway implements RadioReferenceGateway
    {
        private final Map<Integer,AtomicInteger> systemReads = new ConcurrentHashMap<>();
        private final Map<Integer,AtomicInteger> siteReads = new ConcurrentHashMap<>();
        private final Map<Integer,AtomicInteger> talkgroupReads = new ConcurrentHashMap<>();
        private final Map<Integer,AtomicInteger> categoryReads = new ConcurrentHashMap<>();
        private final AtomicInteger systemRefreshes = new AtomicInteger();
        private final AtomicInteger siteRefreshes = new AtomicInteger();
        private volatile String alphaTag = "Dispatch";
        private volatile boolean emptyTalkgroups;
        private List<FrequencyResult> namingFrequencyResults;

        @Override
        public Map<Integer,String> systemTypes()
        {
            return Map.of(1, "Project 25");
        }

        @Override
        public Account account()
        {
            return new Account("test-user", "Never - Test Account");
        }

        @Override
        public List<Country> countries()
        {
            return List.of(new Country(1, "United States", "US"));
        }

        @Override
        public CountryDirectory country(int countryId)
        {
            return new CountryDirectory(new Country(1, "United States", "US"),
                List.of(new State(10, "Test State", "TS")), List.of());
        }

        @Override
        public StateDirectory state(int stateId)
        {
            return new StateDirectory(new State(10, "Test State", "TS"),
                List.of(new County(100, "Franklin", "Franklin County")),
                List.of(new TrunkedSystem(2001, "State P25", "Capital", 1, 2, 3, 1_700_000_000_000L)),
                List.of(new Agency(1001, "State Police", 2)));
        }

        @Override
        public CountyDirectory county(int countyId)
        {
            return new CountyDirectory(new County(100, "Franklin", "Franklin County"), List.of(), List.of());
        }

        @Override
        public List<FrequencyResult> searchStateFrequencies(int stateId, double frequencyMHz)
        {
            if(namingFrequencyResults != null) return namingFrequencyResults;
            return List.of(new FrequencyResult(853.1625, 808.1625, "",
                "State P25 Site 012 Franklin Simulcast", "",
                "34C", "", "", "", "4", "", List.of("Law Dispatch"), 0, 2001, 0, 100));
        }

        @Override
        public List<Mode> modes()
        {
            return List.of(new Mode(4, "Project 25 Phase I"));
        }

        @Override
        public List<Site> sites(int systemId)
        {
            return List.of(new Site(3001, systemId, 12, "Franklin Simulcast", 100,
                List.of(new SiteChannel(853.1625, "c", true, false))));
        }

        @Override
        public List<FrequencyCategory> countyFrequencyCategories(int countyId)
        {
            return List.of(new FrequencyCategory(501, "Public Safety", "Dispatch"));
        }

        @Override
        public ConventionalCatalog conventionalCatalog(DetailKind ownerKind, int ownerId)
        {
            return new ConventionalCatalog("Franklin", "", "", countyFrequencyCategories(ownerId));
        }

        @Override
        public TrunkedSystemDetails trunkedSystemDetails(int systemId)
        {
            systemReads.computeIfAbsent(systemId, ignored -> new AtomicInteger()).incrementAndGet();
            return new TrunkedSystemDetails(systemId, "State P25", "Capital", "Project 25", "Phase I",
                "APCO-25 Common Air Interface", "BEE00", "123");
        }

        @Override
        public TrunkedSystemDetails trunkedSystemDetails(int systemId, boolean refresh)
        {
            if(refresh) systemRefreshes.incrementAndGet();
            return trunkedSystemDetails(systemId);
        }

        @Override
        public List<TrunkedSiteDetails> trunkedSiteDetails(int systemId)
        {
            siteReads.computeIfAbsent(systemId, ignored -> new AtomicInteger()).incrementAndGet();
            return List.of(new TrunkedSiteDetails(3001, systemId, 12, "Franklin Simulcast", 100, 0, 12,
                "34C", 0, "", false,
                List.of(new TrunkedSiteChannel(853_162_500L, 1, "1", "c", "", true, false))));
        }

        @Override
        public List<TrunkedSiteDetails> trunkedSiteDetails(int systemId, boolean refresh)
        {
            if(refresh) siteRefreshes.incrementAndGet();
            return trunkedSiteDetails(systemId);
        }

        @Override
        public List<RemoteTalkgroup> talkgroups(int systemId)
        {
            talkgroupReads.computeIfAbsent(systemId, ignored -> new AtomicInteger()).incrementAndGet();
            if(emptyTalkgroups) return List.of();
            return List.of(new RemoteTalkgroup(9001, 1201, alphaTag, "County dispatch", "D", 0, 701,
                List.of("Law Dispatch")));
        }

        @Override
        public List<RemoteTalkgroupCategory> talkgroupCategories(int systemId)
        {
            categoryReads.computeIfAbsent(systemId, ignored -> new AtomicInteger()).incrementAndGet();
            return List.of(new RemoteTalkgroupCategory(701, systemId, "Public Safety"));
        }

        @Override
        public List<ConventionalFrequency> subcategoryFrequencies(int subCategoryId)
        {
            return List.of(new ConventionalFrequency(8001, 155_250_000L, null, "WXYZ", "County Dispatch",
                "Dispatch", "", "", "", "", "FMN", 0, "", List.of("Law Dispatch"), subCategoryId));
        }

        @Override
        public void close()
        {
        }
    }

    private static final class TestUserPreferences extends UserPreferences
    {
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(Path dataRoot)
        {
            mDirectoryPreference = new DirectoryPreference(preferenceType -> {})
            {
                @Override public Path getDirectoryApplicationRoot() { return dataRoot; }
            };
        }

        @Override public DirectoryPreference getDirectoryPreference() { return mDirectoryPreference; }
    }
}
