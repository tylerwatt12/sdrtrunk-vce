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
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway;
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
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSystem;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteChannel;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteDetails;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSystemDetails;
import io.github.dsheirer.service.radioreference.RadioReferenceImportService;
import io.github.dsheirer.service.radioreference.RadioReferenceImportServiceTestSupport;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadioReferenceHttpControllerTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

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
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"country_id\":1,\"state_id\":10}")));
                assertEquals(1, data(location).at("/country_id").intValue());
                assertEquals(10, settings.stateId);

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

    @Test
    void exposesBoundedBrowseAndImportPreviewContracts() throws Exception
    {
        FakeSettings settings = new FakeSettings();
        FakeGateway gateway = new FakeGateway();
        Path dataRoot = mTemporaryFolder.resolve("import-preview");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);
        manager.init();

        try(RadioReferenceDirectoryService service = new RadioReferenceDirectoryService((user, password) -> gateway))
        {
            service.login("test-user", "secret".toCharArray());
            RadioReferenceHttpController controller = new RadioReferenceHttpController(service, settings,
                new RadioReferenceImportService(service, manager));
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(RadioReferenceHttpController.PATH, controller::handle);
            server.start();

            try
            {
                URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

                assertEquals("Franklin", data(send(client,
                    request(origin, "/counties?state_id=10&search=frank&offset=0&limit=20").GET()))
                    .at("/items/0/name").textValue());
                JsonNode browse = data(send(client, request(origin,
                    "/browse?country_id=1&state_id=10&county_id=100&group=all&scope=all&offset=0&limit=20")
                    .GET()));
                assertTrue(browse.at("/items").isArray());
                assertTrue(browse.at("/items").toString().contains("State P25"));

                JsonNode system = data(send(client,
                    request(origin, "/systems/details?system_id=2001").GET()));
                assertEquals("P25_PHASE2", system.get("recommended_decoder").textValue());
                assertEquals("APCO25", system.get("protocol").textValue());
                assertTrue(system.get("supported").booleanValue());

                JsonNode sites = data(send(client,
                    request(origin, "/systems/sites?system_id=2001&offset=0&limit=20").GET()));
                assertEquals(3001, sites.at("/items/0/id").intValue());
                JsonNode preview = data(send(client,
                    request(origin, "/systems/site-preview?system_id=2001&site_id=3001").GET()));
                assertEquals("P25_PHASE1", preview.get("recommended_decoder").textValue());
                assertTrue(preview.get("p25_modulation_required").booleanValue());
                assertEquals(853_162_500L, preview.at("/default_control_frequencies/0").longValue());

                JsonNode categories = data(send(client, request(origin,
                    "/conventional/categories?owner_kind=agency&owner_id=1001&offset=0&limit=20").GET()));
                assertEquals(444, categories.at("/items/0/sub_category_id").intValue());
                JsonNode frequencies = data(send(client, request(origin,
                    "/conventional/frequencies?sub_category_id=444&search=dispatch&offset=0&limit=20").GET()));
                assertEquals(155_250_000L, frequencies.at("/items/0/downlink_hz").longValue());

                HttpResponse<String> explicitModulationRequired = send(client,
                    jsonRequest(origin, "/systems/channels").POST(HttpRequest.BodyPublishers.ofString(
                        "{\"system_id\":2001,\"site_id\":3001}")));
                assertEquals(400, explicitModulationRequired.statusCode());
                assertEquals("invalid_request", OBJECT_MAPPER.readTree(explicitModulationRequired.body())
                    .at("/error/code").textValue());

                HttpResponse<String> unknownField = send(client,
                    jsonRequest(origin, "/systems/channels").POST(HttpRequest.BodyPublishers.ofString(
                        "{\"system_id\":2001,\"site_id\":3001,\"p25_modulation\":\"C4FM\"," +
                            "\"untrusted_frequency\":851000000}")));
                assertEquals(400, unknownField.statusCode());
            }
            finally
            {
                server.stop(0);
            }
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void requiresUpdateConfirmationAndReportsImportPersistenceFailuresAsUnavailable() throws Exception
    {
        FakeSettings settings = new FakeSettings();
        FakeGateway gateway = new FakeGateway();
        gateway.talkgroupCategories = List.of(new RemoteTalkgroupCategory(9, 2001, "Dispatch"));
        gateway.talkgroups = List.of(new RemoteTalkgroup(101, 56_001, "County Fire", "Fire dispatch", "D",
            0, 9, List.of("Fire Dispatch")));
        Path dataRoot = mTemporaryFolder.resolve("import-failures");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        FailingConfigurationManager manager = new FailingConfigurationManager(new TestUserPreferences(dataRoot));
        manager.init();
        AliasAdministrationService administration = AliasAdministrationServiceTestSupport.create(manager);
        AliasAdministrationService.MutationResult aliasList = administration.createAliasList("County P25",
            AliasListFamily.P25, administration.currentRevision());

        try(RadioReferenceDirectoryService service = new RadioReferenceDirectoryService((user, password) -> gateway))
        {
            service.login("test-user", "secret".toCharArray());
            RadioReferenceImportService importer = RadioReferenceImportServiceTestSupport.create(service, manager,
                administration);
            RadioReferenceHttpController controller = new RadioReferenceHttpController(service, settings, importer);
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(RadioReferenceHttpController.PATH, controller::handle);
            server.start();

            try
            {
                URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                String addBody = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "system_id", 2001, "alias_list_id", aliasList.aliasListId(),
                    "revision", aliasList.revision(), "talkgroup_ids", List.of(101)));
                JsonNode added = data(send(client, jsonRequest(origin, "/systems/talkgroups/import")
                    .POST(HttpRequest.BodyPublishers.ofString(addBody))));
                long revision = added.get("revision").longValue();
                assertEquals(1, added.get("added").intValue(),
                    "a new-only selection must not require update confirmation");

                gateway.talkgroups = List.of(new RemoteTalkgroup(101, 56_001, "County Fire Updated",
                    "Updated fire dispatch", "D", 0, 9, List.of("Fire Dispatch")));
                String unconfirmedBody = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "system_id", 2001, "alias_list_id", aliasList.aliasListId(),
                    "revision", revision, "talkgroup_ids", List.of(101)));
                HttpResponse<String> unconfirmed = send(client,
                    jsonRequest(origin, "/systems/talkgroups/import")
                        .POST(HttpRequest.BodyPublishers.ofString(unconfirmedBody)));
                assertEquals(409, unconfirmed.statusCode());
                assertEquals("confirmation_required", OBJECT_MAPPER.readTree(unconfirmed.body())
                    .at("/error/code").textValue());
                assertEquals("County Fire", manager.getAliasModel().getAliases().getFirst().getName());

                manager.mFailAliasCommit.set(true);
                String confirmedBody = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "system_id", 2001, "alias_list_id", aliasList.aliasListId(),
                    "revision", revision, "talkgroup_ids", List.of(101), "confirm_updates", true));
                HttpResponse<String> aliasFailure = send(client,
                    jsonRequest(origin, "/systems/talkgroups/import")
                        .POST(HttpRequest.BodyPublishers.ofString(confirmedBody)));
                assertStorageUnavailable(aliasFailure);
                assertEquals("County Fire", manager.getAliasModel().getAliases().getFirst().getName());

                manager.mFailAliasCommit.set(false);
                manager.mFailChannelFlush.set(true);
                String conventionalBody = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "sub_category_id", 444, "frequency_ids", List.of(77),
                    "system_name", "Franklin County", "site_name", "Public Safety"));
                HttpResponse<String> channelFailure = send(client,
                    jsonRequest(origin, "/conventional/channels")
                        .POST(HttpRequest.BodyPublishers.ofString(conventionalBody)));
                assertStorageUnavailable(channelFailure);
                assertTrue(manager.getChannelModel().getChannels().isEmpty(),
                    "a failed channel save must roll back its in-memory addition");
            }
            finally
            {
                server.stop(0);
            }
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    private static void assertStorageUnavailable(HttpResponse<String> response) throws Exception
    {
        assertEquals(503, response.statusCode());
        assertEquals("storage_unavailable", OBJECT_MAPPER.readTree(response.body())
            .at("/error/code").textValue());
        assertFalse(response.body().contains("Simulated"), "internal persistence details must not be exposed");
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
        private boolean credentialsStored;
        private String userName;
        private String password;
        private int countryId = -1;
        private int stateId = -1;

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
        public void storeLocation(int countryId, int stateId)
        {
            this.countryId = countryId;
            this.stateId = stateId;
        }
    }

    private static final class FakeGateway implements RadioReferenceGateway
    {
        private List<RemoteTalkgroup> talkgroups = List.of();
        private List<RemoteTalkgroupCategory> talkgroupCategories = List.of();

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
                List.of(new TrunkedSystem(2001, "State P25", "Capital", 1, 2, 3)),
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
        public TrunkedSystemDetails trunkedSystemDetails(int systemId)
        {
            return new TrunkedSystemDetails(2001, "State P25", "Capital", "Project 25", "Phase II",
                "APCO-25 Common Air Interface", "BEE00", "49F");
        }

        @Override
        public List<RemoteTalkgroup> talkgroups(int systemId)
        {
            return talkgroups;
        }

        @Override
        public List<RemoteTalkgroupCategory> talkgroupCategories(int systemId)
        {
            return talkgroupCategories;
        }

        @Override
        public List<TrunkedSiteDetails> trunkedSiteDetails(int systemId)
        {
            return List.of(new TrunkedSiteDetails(3001, 2001, 12, "Franklin Simulcast", 100, 1, 1,
                "491", 0, "LSM", false, List.of(
                    new TrunkedSiteChannel(853_162_500L, 1, "1", "c", "", true, false),
                    new TrunkedSiteChannel(852_900_000L, 2, "2", "a", "", false, true))));
        }

        @Override
        public List<FrequencyCategory> agencyFrequencyCategories(int agencyId)
        {
            return List.of(new FrequencyCategory(444, "Public Safety", "Dispatch"));
        }

        @Override
        public List<ConventionalFrequency> subcategoryFrequencies(int subCategoryId)
        {
            return List.of(new ConventionalFrequency(77, 155_250_000L, null, "WQAB123", "County Fire",
                "Fire Dispatch", "123.0 PL", "", "", "", "FMN", 0, "RM",
                List.of("Fire Dispatch"), 444));
        }

        @Override
        public void close()
        {
        }
    }

    private static final class FailingConfigurationManager extends ConfigurationManager
    {
        private final AtomicBoolean mFailAliasCommit = new AtomicBoolean();
        private final AtomicBoolean mFailChannelFlush = new AtomicBoolean();

        private FailingConfigurationManager(UserPreferences userPreferences)
        {
            super(userPreferences, null, new AliasModel(), null, null);
        }

        @Override
        public synchronized AliasConfigurationSnapshot commitAndPublishAliasConfiguration(
            AliasConfigurationSnapshot proposed, AliasConfigurationPublication publication,
            Runnable beforePublication, BroadcastConfigurationRename broadcastRename)
        {
            if(mFailAliasCommit.get())
            {
                throw new ConfigurationCommitException("Simulated Alias storage failure",
                    new IllegalStateException("test storage unavailable"));
            }

            return super.commitAndPublishAliasConfiguration(proposed, publication, beforePublication,
                broadcastRename);
        }

        @Override
        public void flushConfiguration()
        {
            if(mFailChannelFlush.get())
            {
                throw new IllegalStateException("Simulated channel storage failure");
            }

            super.flushConfiguration();
        }
    }

    private static final class TestUserPreferences extends UserPreferences
    {
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(Path dataRoot)
        {
            mDirectoryPreference = new DirectoryPreference(_ -> { })
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
