/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.record.RecordingMode;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.AccessTier;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the real HTTP, RBAC, range, settings, and deletion contract across the separate catalog. */
class ManagedRecordingsHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "test-recordings-password";

    @TempDir
    Path mDirectory;

    private UserPreferences mPreferences;
    private RecordingMode mOriginalMode;
    private Integer mOriginalRetention;
    private boolean mOriginalTranscriptionEnabled;
    private String mOriginalTranscriptionUrl;
    private String mOriginalTranscriptionModel;
    private String mOriginalTranscriptionApiKey;
    private long mOriginalTranscriptionMinimumDurationMs;
    private volatile ManagedRecordingCatalog mCatalog;
    private ManagedRecordingMaintenance mMaintenance;
    private WebRequestSecurity mSecurity;
    private HttpServer mServer;
    private ExecutorService mExecutor;
    private HttpClient mClient;
    private URI mOrigin;
    private WebAccessService mAccess;
    private final AtomicLong mBrowseClock = new AtomicLong();

    @BeforeEach
    void setUp() throws Exception
    {
        mPreferences = new UserPreferences();
        mOriginalMode = mPreferences.getRecordPreference().getRecordingMode();
        mOriginalRetention = mPreferences.getRecordPreference().getManagedRetentionDays();
        mOriginalTranscriptionEnabled = mPreferences.getRecordPreference().isTranscriptionEnabled();
        mOriginalTranscriptionUrl = mPreferences.getRecordPreference().getTranscriptionUrl();
        mOriginalTranscriptionModel = mPreferences.getRecordPreference().getTranscriptionModel();
        mOriginalTranscriptionApiKey = mPreferences.getRecordPreference().getTranscriptionApiKey();
        mOriginalTranscriptionMinimumDurationMs =
            mPreferences.getRecordPreference().getTranscriptionMinimumDurationMs();
        mPreferences.getRecordPreference().setRecordingMode(RecordingMode.CLASSIC);
        mPreferences.getRecordPreference().setTranscriptionEnabled(false);
        mPreferences.getRecordPreference().setTranscriptionUrl("");
        mPreferences.getRecordPreference().setTranscriptionModel("");
        mPreferences.getRecordPreference().setTranscriptionApiKey("");
        mPreferences.getRecordPreference().setTranscriptionMinimumDurationMs(500);
        Path mainDatabase = mDirectory.resolve("main.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(mainDatabase);
        Path root = mDirectory.resolve("recordings-managed");
        Path catalogDatabase = mDirectory.resolve("managed-recordings.sqlite");
        mCatalog = new ManagedRecordingCatalog(catalogDatabase, root);
        Files.write(root.resolve("one.mp3"), new byte[]{'I', 'D', '3', 'x', 'y'});
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + catalogDatabase);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,protocol,call_type,voice_type,source_id,target_id) " +
                "VALUES(1000,2000,1000,'one.mp3',5,1,1,1,101,4001)");
            statement.executeUpdate("UPDATE catalog_metadata SET call_count=1,total_bytes=5 WHERE id=1");
        }

        WebAccessService access = new WebAccessService(mainDatabase);
        mAccess = access;
        char[] password = PASSWORD.toCharArray();
        try
        {
            access.provisionOrResetPrimaryAdmin(password);
        }
        finally
        {
            Arrays.fill(password, '\0');
        }
        WebAuthenticationService authentication = new WebAuthenticationService(access);
        mSecurity = new WebRequestSecurity(access, authentication);
        mMaintenance = new ManagedRecordingMaintenance();
        mServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        mExecutor = Executors.newCachedThreadPool();
        mServer.setExecutor(mExecutor);
        new WebSessionHttpController(access, authentication, mSecurity).register(mServer);
        ManagedRecordingsHttpController controller = new ManagedRecordingsHttpController(() -> mCatalog,
            mPreferences, mSecurity, mMaintenance, new ManagedRecordingLabels(mainDatabase), () -> null,
            new StatsBrowseReadCache(4, mBrowseClock::get));
        mServer.createContext(ManagedRecordingsHttpController.BROWSE_PATH,
            mSecurity.protectApi(WebCapability.RECORDINGS_VIEW, controller::handleBrowse));
        mServer.createContext(ManagedRecordingsHttpController.ADMIN_PATH,
            mSecurity.protectApi(WebCapability.ADMIN_RECORDINGS, controller::handleAdmin));
        mServer.start();
        mOrigin = URI.create("http://127.0.0.1:" + mServer.getAddress().getPort());
        mClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown()
    {
        if(mServer != null) mServer.stop(0);
        if(mSecurity != null) mSecurity.close();
        if(mMaintenance != null) mMaintenance.close();
        if(mCatalog != null) mCatalog.close();
        if(mExecutor != null) mExecutor.shutdownNow();
        if(mPreferences != null)
        {
            mPreferences.getRecordPreference().setRecordingMode(mOriginalMode);
            mPreferences.getRecordPreference().setManagedRetentionDays(mOriginalRetention);
            mPreferences.getRecordPreference().setTranscriptionEnabled(mOriginalTranscriptionEnabled);
            mPreferences.getRecordPreference().setTranscriptionUrl(mOriginalTranscriptionUrl);
            mPreferences.getRecordPreference().setTranscriptionModel(mOriginalTranscriptionModel);
            mPreferences.getRecordPreference().setTranscriptionApiKey(mOriginalTranscriptionApiKey);
            mPreferences.getRecordPreference().setTranscriptionMinimumDurationMs(
                mOriginalTranscriptionMinimumDurationMs);
        }
    }

    @Test
    void blockedRecordingRecountDoesNotHoldStatusBrowsingOrSettings() throws Exception
    {
        assertBlockedRecordingMaintenance("recount");
    }

    @Test
    void blockedRecordingReindexDoesNotHoldStatusBrowsingOrSettings() throws Exception
    {
        assertBlockedRecordingMaintenance("reindex");
    }

    private void assertBlockedRecordingMaintenance(String operation) throws Exception
    {
        Session admin = login();
        // A size mismatch makes either operation need the catalog writer after its first bounded file batch.
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mCatalog.databaseFile());
            Statement writer = connection.createStatement())
        {
            writer.executeUpdate("UPDATE recording_call SET size_bytes=4 WHERE id=1");
            writer.execute("BEGIN IMMEDIATE");
            try
            {
                HttpResponse<String> accepted = send(mutation(ManagedRecordingsHttpController.ADMIN_PATH +
                    "/" + operation, admin).POST(HttpRequest.BodyPublishers.noBody()));
                assertEquals(202, accepted.statusCode(), accepted.body());
                assertEquals("RUNNING", json(accepted).at("/data/state").textValue());
                assertEquals(409, send(mutation(ManagedRecordingsHttpController.ADMIN_PATH + "/recount", admin)
                    .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
                assertRecordingNavigationRemainsAvailable(admin);
                assertEquals("RUNNING", mMaintenance.status().state(), "The writer is still held by this test");
            }
            finally { writer.execute("ROLLBACK"); }
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while("RUNNING".equals(mMaintenance.status().state()) && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals("COMPLETED", mMaintenance.status().state());
    }

    @Test
    void maximumRecordingDeletionLeavesStatusAndNavigationAvailableWhileTheWriterIsBlocked() throws Exception
    {
        Session admin = login();
        Path root = mDirectory.resolve("recordings-managed");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mCatalog.databaseFile());
            var insert = connection.prepareStatement("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms," +
                "relative_path,size_bytes,protocol,call_type,voice_type) VALUES(?,1000,2000,1000,?,5,1,1,1)"))
        {
            for(int id = 2; id <= 100; id++)
            {
                String file = "bulk-" + id + ".mp3";
                Files.write(root.resolve(file), new byte[]{'I', 'D', '3', 'x', 'y'});
                insert.setInt(1, id);
                insert.setString(2, file);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        String body = MAPPER.writeValueAsString(Map.of("ids", java.util.stream.IntStream.rangeClosed(1, 100)
            .boxed().toList()));
        CompletableFuture<HttpResponse<String>> deletion;
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mCatalog.databaseFile());
            Statement writer = connection.createStatement())
        {
            writer.execute("BEGIN IMMEDIATE");
            try
            {
                deletion = mClient.sendAsync(mutation(ManagedRecordingsHttpController.ADMIN_PATH + "/calls", admin)
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while(Files.exists(root.resolve("one.mp3")) && System.nanoTime() < deadline) Thread.sleep(10);
                assertFalse(Files.exists(root.resolve("one.mp3")), "The delete reached the held catalog writer");
                assertFalse(deletion.isDone());
                assertRecordingNavigationRemainsAvailable(admin);
                assertEquals(409, send(mutation(ManagedRecordingsHttpController.ADMIN_PATH + "/calls", admin)
                    .method("DELETE", HttpRequest.BodyPublishers.ofString("{\"ids\":[100]}"))).statusCode());
                assertFalse(deletion.isDone(), "Navigation completed before the catalog writer was released");
            }
            finally { writer.execute("ROLLBACK"); }
        }
        HttpResponse<String> completed = deletion.get(5, TimeUnit.SECONDS);
        assertEquals(200, completed.statusCode(), completed.body());
        assertEquals(100, json(completed).at("/data/deleted").intValue());
        try(var files = Files.list(root))
        {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    private void assertRecordingNavigationRemainsAvailable(Session admin) throws Exception
    {
        List<String> paths = List.of(ManagedRecordingsHttpController.ADMIN_PATH + "/status",
            ManagedRecordingsHttpController.ADMIN_PATH + "/settings",
            ManagedRecordingsHttpController.BROWSE_PATH + "/status",
            ManagedRecordingsHttpController.BROWSE_PATH + "/calls?from_ms=0&to_ms=5000");
        var reads = java.util.stream.IntStream.range(0, 20).mapToObj(index -> mClient.sendAsync(
            request(paths.get(index % paths.size())).header("Cookie", admin.cookie()).GET().build(),
            HttpResponse.BodyHandlers.ofString())).toList();
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
        for(var read: reads)
        {
            HttpResponse<String> response = read.join();
            assertEquals(200, response.statusCode(), response.body());
        }
    }

    @Test
    void publicPlaybackAndPrimaryAdminManagementUseTheSameCatalog() throws Exception
    {
        String browse = ManagedRecordingsHttpController.BROWSE_PATH;
        String admin = ManagedRecordingsHttpController.ADMIN_PATH;
        HttpResponse<String> initialStatus = send(request(browse + "/status").GET());
        assertEquals(200, initialStatus.statusCode(), initialStatus.body());
        assertEquals("CLASSIC", json(initialStatus).at("/data/mode").textValue());
        assertTrue(json(initialStatus).at("/data/available").booleanValue());
        assertTrue(json(initialStatus).at("/data/has_calls").booleanValue());
        assertEquals(1, json(initialStatus).at("/data/call_count").longValue());
        assertFalse(json(initialStatus).at("/data").has("managed_directory"));
        assertFalse(json(initialStatus).at("/data").has("retention_days"));
        assertEquals(400, send(request(browse + "/status?unexpected=1").GET()).statusCode());
        HttpResponse<String> search = send(request(browse + "/calls?from_ms=0&to_ms=5000")
            .GET());
        assertEquals(200, search.statusCode(), search.body());
        assertEquals(1, json(search).at("/data/calls").size());
        assertEquals(4001, json(search).at("/data/calls/0/talkgroup_id").intValue());
        assertEquals(200, send(request(browse + "/calls/1").GET()).statusCode());
        HttpResponse<String> audio = send(request(browse + "/calls/1/audio")
            .header("Range", "bytes=1-3").GET());
        assertEquals(206, audio.statusCode(), audio.body());
        assertEquals("D3x", audio.body());
        assertEquals("bytes 1-3/5", audio.headers().firstValue("Content-Range").orElseThrow());
        assertEquals(416, send(request(browse + "/calls/1/audio")
            .header("Range", "bytes=9-").GET()).statusCode());
        assertEquals(401, send(request(admin + "/settings").GET()).statusCode());

        Session primary = login();
        assertEquals(403, send(request(admin + "/calls")
            .header("Origin", mOrigin.toString()).header("Cookie", primary.cookie())
            .header("Content-Type", "application/json")
            .method("DELETE", HttpRequest.BodyPublishers.ofString("{\"ids\":[1]}"))).statusCode());
        HttpResponse<String> update = send(mutation(admin + "/settings", primary)
            .PUT(HttpRequest.BodyPublishers.ofString("{\"mode\":\"MANAGED\",\"retention_days\":30}")));
        assertEquals(200, update.statusCode(), update.body());
        assertEquals("MANAGED", json(update).at("/data/mode").textValue());
        assertEquals(30, json(update).at("/data/retention_days").intValue());
        assertEquals("MANAGED", json(send(request(browse + "/status").GET()))
            .at("/data/mode").textValue());
        assertEquals(200, send(request(admin + "/status")
            .header("Cookie", primary.cookie()).GET()).statusCode());
        HttpResponse<String> deletion = send(mutation(admin + "/calls", primary)
            .method("DELETE", HttpRequest.BodyPublishers.ofString("{\"ids\":[1]}")));
        assertEquals(200, deletion.statusCode(), deletion.body());
        assertEquals(1, json(deletion).at("/data/deleted").intValue());
        assertFalse(Files.exists(mDirectory.resolve("recordings-managed/one.mp3")));
        assertEquals(0, json(send(request(browse + "/calls?from_ms=0&to_ms=5000").GET()))
            .at("/data/calls").size());
        HttpResponse<String> emptyStatus = send(request(browse + "/status").GET());
        assertFalse(json(emptyStatus).at("/data/has_calls").booleanValue());
        assertEquals(0, json(emptyStatus).at("/data/call_count").longValue());
        assertTrue(json(send(request(admin + "/status").header("Cookie", primary.cookie()).GET()))
            .at("/data/catalog/call_count").isNumber());
    }

    @Test
    void cachedBrowseResponsesRemainBehindCurrentRequestAuthorization() throws Exception
    {
        String path = ManagedRecordingsHttpController.BROWSE_PATH + "/calls?from_ms=0&to_ms=5000";
        mAccess.setCapabilityTier(WebCapability.RECORDINGS_VIEW, AccessTier.USER);
        assertEquals(401, send(request(path).GET()).statusCode());
        Session primary = login();
        HttpResponse<String> allowed = send(request(path).header("Cookie", primary.cookie()).GET());
        assertEquals(200, allowed.statusCode(), allowed.body());
        assertEquals(401, send(request(path).GET()).statusCode(), "A cached authorized read must not authorize a peer");
        mAccess.setCapabilityTier(WebCapability.RECORDINGS_VIEW, AccessTier.PUBLIC);
        assertEquals(allowed.body(), send(request(path).GET()).body());
        mAccess.setCapabilityTier(WebCapability.RECORDINGS_VIEW, AccessTier.USER);
        assertEquals(401, send(request(path).GET()).statusCode(), "Changed policy also protects a warm public cache");
    }

    @Test
    void replacementCatalogDoesNotReuseThePriorCatalogsPreparedResponse() throws Exception
    {
        String path = ManagedRecordingsHttpController.BROWSE_PATH + "/calls?from_ms=0&to_ms=5000";
        assertEquals(101, json(send(request(path).GET())).at("/data/calls/0/source_id").intValue());
        String status = ManagedRecordingsHttpController.BROWSE_PATH + "/status";
        assertEquals(1, json(send(request(status).GET())).at("/data/call_count").intValue());
        ManagedRecordingCatalog original = mCatalog;
        try(ManagedRecordingCatalog replacement = new ManagedRecordingCatalog(
            mDirectory.resolve("replacement.sqlite"), mDirectory.resolve("replacement-audio")))
        {
            mCatalog = replacement;
            assertEquals(0, json(send(request(status).GET())).at("/data/call_count").intValue());
            try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + replacement.databaseFile());
                Statement statement = connection.createStatement())
            {
                statement.executeUpdate("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path," +
                    "size_bytes,protocol,call_type,voice_type,source_id,target_id) " +
                    "VALUES(1000,2000,1000,'replacement.mp3',5,1,1,1,202,4001)");
            }
            mCatalog = replacement;
            assertEquals(202, json(send(request(path).GET())).at("/data/calls/0/source_id").intValue());
        }
        finally { mCatalog = original; }
        assertEquals(101, json(send(request(path).GET())).at("/data/calls/0/source_id").intValue());
        assertEquals(1, json(send(request(status).GET())).at("/data/call_count").intValue());
    }

    @Test
    void publicStatusDistinguishesUnavailableCatalogFromEmptyCatalog() throws Exception
    {
        ManagedRecordingCatalog catalog = mCatalog;
        mCatalog = null;
        try
        {
            HttpResponse<String> response = send(request(
                ManagedRecordingsHttpController.BROWSE_PATH + "/status").GET());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode data = json(response).at("/data");
            assertFalse(data.get("available").booleanValue());
            assertFalse(data.get("has_calls").booleanValue());
            assertTrue(data.get("call_count").isNull());
            assertEquals(503, send(request(
                ManagedRecordingsHttpController.BROWSE_PATH + "/calls").GET()).statusCode());
        }
        finally
        {
            mCatalog = catalog;
        }
    }

    @Test
    void selectedP25SystemFindsHistoricalCallWithSiteEvidenceAndNoStoredSystemKey() throws Exception
    {
        String configurationId = "4b75217f-2555-4c38-aafc-5d17bc0faf71";
        String systemKey = "p25:bee00:49f";
        String otherSystemKey = "p25:bee00:4a0";
        try(Connection connection = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) " +
                "VALUES(91,'Fixture Aliases','P25')");
            statement.executeUpdate("INSERT INTO configuration_channel(configuration_id,channel_kind," +
                "sort_order,system_name,site_name,name,alias_list_id,decoder_type," +
                "primary_frequency_hz,config_json) VALUES('" + configurationId +
                "','TRUNKED',91,'Fixture Radio System','Fixture Site','Fixture Control'," +
                "91,'P25_PHASE1',853375000,'{}')");
            statement.executeUpdate("INSERT INTO radio_system(id,system_key,protocol_code," +
                "address_domain_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms) " +
                "VALUES(91,'" + systemKey + "',1,0,0xBEE00,0x49F,1000,2000)," +
                "(92,'" + otherSystemKey + "',1,0,0xBEE00,0x4A0,1000,2000)");
            statement.executeUpdate("INSERT INTO receiver_channel(id,configuration_id," +
                "first_seen_ms,last_seen_ms,radio_system_id,radio_system_assigned_at_ms) " +
                "VALUES(91,'" + configurationId + "',1000,2000,91,1000)");
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO recording_channel(id,channel_uuid) VALUES(91,'" +
                configurationId + "')");
            statement.executeUpdate("INSERT INTO recording_site(id,wacn,system_id,rfss,site_id) " +
                "VALUES(91,0xBEE00,0x49F,1,1)");
            statement.executeUpdate("UPDATE recording_call SET channel_id=91,winner_site_id=91 " +
                "WHERE id=1");
            statement.executeUpdate("INSERT INTO recording_call_site(call_id,site_id,start_ms) " +
                "VALUES(1,91,1000)");
        }

        String browse = ManagedRecordingsHttpController.BROWSE_PATH;
        JsonNode unfiltered = json(send(request(browse + "/calls?from_ms=0&to_ms=5000").GET()))
            .at("/data/calls/0");
        assertEquals("Fixture Radio System", unfiltered.path("system_name").textValue());
        assertEquals(systemKey, unfiltered.path("system_key").textValue());

        HttpResponse<String> suggestions = send(request(browse +
            "/suggestions?from_ms=0&to_ms=5000&q=Fixture%20Radio%20System&kind=system").GET());
        assertEquals(200, suggestions.statusCode(), suggestions.body());
        JsonNode selected = json(suggestions).at("/data/0");
        assertEquals("Fixture Radio System", selected.path("label").textValue());
        JsonNode numeric = json(send(request(browse +
            "/suggestions?from_ms=0&to_ms=5000&q=4001&kind=talkgroup&system_key=" + systemKey).GET())).at("/data");
        assertEquals(1, numeric.size());
        assertEquals("4001", numeric.get(0).path("id").textValue());
        try(Connection connection = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms," +
                "protocol,rfss,site) VALUES(91,1000,2000,'APCO25',1,1)");
        }
        JsonNode siteRows = json(send(request(browse + "/suggestions?from_ms=0&to_ms=5000&q=1&kind=site&system_key=" +
            systemKey).GET())).at("/data");
        assertEquals(1, siteRows.size());
        assertEquals("Fixture Control", siteRows.get(0).path("label").textValue());
        assertEquals("Fixture Site · Fixture Radio System", siteRows.get(0).path("detail").textValue());
        assertEquals(configurationId, siteRows.get(0).path("channel_id").textValue());

        assertEquals(systemKey, selected.path("system_key").textValue());

        HttpResponse<String> matching = send(request(browse + "/calls?from_ms=0&to_ms=5000" +
            "&system_key=" + systemKey).GET());
        assertEquals(200, matching.statusCode(), matching.body());
        assertEquals(1, json(matching).at("/data/calls").size());
        assertEquals(1, json(matching).at("/data/calls/0/id").longValue());

        HttpResponse<String> unrelated = send(request(browse + "/calls?from_ms=0&to_ms=5000" +
            "&system_key=" + otherSystemKey).GET());
        assertEquals(200, unrelated.statusCode(), unrelated.body());
        assertEquals(0, json(unrelated).at("/data/calls").size());
    }

    @Test
    void transcriptionSettingsAndDetailsKeepKeyPrivateAndEnforceEligibility() throws Exception
    {
        String browse = ManagedRecordingsHttpController.BROWSE_PATH;
        String admin = ManagedRecordingsHttpController.ADMIN_PATH;
        Session primary = login();
        HttpResponse<String> initial = send(request(admin + "/settings")
            .header("Cookie", primary.cookie()).GET());
        assertEquals(200, initial.statusCode(), initial.body());
        assertFalse(json(initial).at("/data/transcription_enabled").booleanValue());
        assertEquals(500, json(initial).at("/data/transcription_min_duration_ms").longValue());
        assertFalse(json(initial).at("/data/transcription_key_configured").booleanValue());
        assertFalse(json(initial).at("/data").has("transcription_api_key"));
        assertEquals("disabled", json(send(request(browse + "/calls/1").GET()))
            .at("/data/transcription/status").textValue());
        assertEquals(400, send(mutation(admin + "/settings", primary)
            .PUT(HttpRequest.BodyPublishers.ofString("{\"transcription_enabled\":true}"))).statusCode());
        assertEquals(400, send(mutation(admin + "/settings", primary)
            .PUT(HttpRequest.BodyPublishers.ofString("{\"transcription_url\":\"file:///etc/passwd\"}")))
            .statusCode());
        assertEquals(400, send(mutation(admin + "/settings", primary)
            .PUT(HttpRequest.BodyPublishers.ofString("{\"transcription_min_duration_ms\":600001}")))
            .statusCode());
        assertEquals(400, send(mutation(admin + "/settings", primary)
            .PUT(HttpRequest.BodyPublishers.ofString("{\"transcription_min_duration_ms\":499}")))
            .statusCode());

        String key = "test-transcription-secret";
        HttpResponse<String> configured = send(mutation(admin + "/settings", primary)
            .PUT(HttpRequest.BodyPublishers.ofString("{\"transcription_url\":\"http://127.0.0.1:8000/" +
                "v1/audio/transcriptions\",\"transcription_model\":\"radio-model\"," +
                "\"transcription_api_key\":\"" + key + "\",\"transcription_enabled\":true}")));
        assertEquals(200, configured.statusCode(), configured.body());
        assertTrue(json(configured).at("/data/transcription_enabled").booleanValue());
        assertTrue(json(configured).at("/data/transcription_key_configured").booleanValue());
        assertFalse(configured.body().contains(key));
        assertFalse(json(configured).at("/data").has("transcription_api_key"));
        HttpResponse<String> status = send(request(admin + "/status")
            .header("Cookie", primary.cookie()).GET());
        assertEquals(1, json(status).at("/data/transcription/pending").longValue());
        assertFalse(status.body().contains(key));
        HttpResponse<String> detail = send(request(browse + "/calls/1").GET());
        assertEquals("pending", json(detail).at("/data/transcription/status").textValue());

        HttpResponse<String> threshold = send(mutation(admin + "/settings", primary)
            .PUT(HttpRequest.BodyPublishers.ofString("{\"transcription_min_duration_ms\":1500}")));
        assertEquals(200, threshold.statusCode(), threshold.body());
        assertEquals("too_short", json(send(request(browse + "/calls/1").GET()))
            .at("/data/transcription/status").textValue());
        assertEquals(409, send(mutation(admin + "/calls/1/transcription/retry", primary)
            .POST(HttpRequest.BodyPublishers.noBody())).statusCode());

        send(mutation(admin + "/settings", primary)
            .PUT(HttpRequest.BodyPublishers.ofString("{\"transcription_min_duration_ms\":500," +
                "\"transcription_clear_api_key\":true}")));
        assertFalse(json(send(request(admin + "/settings").header("Cookie", primary.cookie()).GET()))
            .at("/data/transcription_key_configured").booleanValue());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE recording_call SET transcription_status='failed' WHERE id=1");
        }
        assertEquals("failed", json(send(request(browse + "/calls/1").GET()))
            .at("/data/transcription/status").textValue());
        assertEquals(202, send(mutation(admin + "/calls/1/transcription/retry", primary)
            .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
        assertEquals("pending", json(send(request(browse + "/calls/1").GET()))
            .at("/data/transcription/status").textValue());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO recording_transcript(call_id,text,stored_at_ms) " +
                "VALUES(1,'Dispatch test',3000)");
            statement.executeUpdate("UPDATE recording_call SET transcription_status='complete' WHERE id=1");
        }
        JsonNode completed = json(send(request(browse + "/calls/1").GET())).at("/data/transcription");
        assertEquals("complete", completed.get("status").textValue());
        assertEquals("Dispatch test", completed.get("text").textValue());
        assertEquals(3000, completed.get("stored_at_ms").longValue());
        String search = browse + "/calls?from_ms=0&to_ms=5000&transcript=dispatch&text_range=time";
        JsonNode matches = json(send(request(search).GET())).at("/data/calls");
        assertEquals(1, matches.size());
        assertEquals("Dispatch test", matches.get(0).get("transcript_excerpt").textValue());
        assertEquals(0, json(send(request(browse +
            "/calls?from_ms=0&to_ms=5000&transcript=unmatched").GET())).at("/data/calls").size());
        assertEquals(400, send(request(browse + "/calls?transcript=" + "a".repeat(241)).GET()).statusCode());
    }

    @Test
    void patchedTalkgroupNamesFindTheSameRecordingAsTheirNumericIdentity() throws Exception
    {
        try(Connection connection = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES" +
                "(700,'Fixture Recorded List','P25'),(701,'Fixture Other List','P25')");
            statement.executeUpdate("INSERT INTO alias(alias_list_id,name,description,group_name," +
                "matcher_type,protocol,value) VALUES" +
                "(700,'Harbor Dispatch','County dispatch desk','Public Safety','TALKGROUP','APCO25',1001)," +
                "(701,'Other Dispatch','Unrelated description','Unrelated group','TALKGROUP','APCO25',1001)");
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE recording_call SET alias_list_id=700,call_type=2 WHERE id=1");
            statement.executeUpdate("INSERT INTO recording_patch_member(call_id,kind,local_id,home_wacn," +
                "home_system,home_id,start_ms) VALUES(1,1,1001,-1,-1,-1,1000)");
        }
        String base = ManagedRecordingsHttpController.BROWSE_PATH + "/calls?from_ms=0&to_ms=5000";
        assertEquals(1, json(send(request(base + "&talkgroup_id=1001").GET())).at("/data/calls").size());
        for(String query : List.of("Harbor", "County", "Public"))
        {
            HttpResponse<String> response = send(request(base + "&q=" + query).GET());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(1, json(response).at("/data/calls").size(), query);
            assertEquals("Harbor Dispatch", json(response).at("/data/calls/0/patch_members/0/alias").textValue());
        }
        assertEquals(0, json(send(request(base + "&q=Unrelated").GET())).at("/data/calls").size());
    }

    @Test
    void nxdnZeroRecordingKeepsItsAliasInBrowsingDetailsAndFriendlyNameFilters() throws Exception
    {
        try(Connection connection = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(700,'NXDN Conventional','NXDN')");
            statement.executeUpdate("INSERT INTO alias(alias_list_id,name,description,group_name,matcher_type,protocol,value) " +
                "VALUES(700,'Harbor Dispatch','County operations','Public Works','TALKGROUP','NXDN',0)");
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE recording_call SET alias_list_id=700,protocol=4,target_id=0 WHERE id=1");
        }
        String browse = ManagedRecordingsHttpController.BROWSE_PATH;
        JsonNode detail = json(send(request(browse + "/calls/1").GET())).at("/data");
        assertEquals(0, detail.get("talkgroup_id").intValue());
        assertEquals("Harbor Dispatch", detail.get("group_alias").textValue());
        assertEquals("County operations", detail.get("group_description").textValue());
        assertEquals("Public Works", detail.get("group_group").textValue());
        for(String query: List.of("Harbor", "County", "Public"))
        {
            HttpResponse<String> response = send(request(browse + "/calls?from_ms=0&to_ms=5000&q=" + query).GET());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode calls = json(response).at("/data/calls");
            assertEquals(1, calls.size(), query);
            assertEquals(0, calls.get(0).get("talkgroup_id").intValue());
            assertEquals("Harbor Dispatch", calls.get(0).get("group_alias").textValue());
        }
        assertEquals(0, json(send(request(browse + "/calls?from_ms=0&to_ms=5000&q=Unrelated").GET()))
            .at("/data/calls").size());
        assertEquals(200, send(request(browse + "/calls/1/audio").GET()).statusCode());
    }

    @Test
    void foreignCanonicalPatchGroupCannotMatchAnUnrelatedLocalAliasName() throws Exception
    {
        try(Connection connection = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(700,'Fixture Local List','P25')");
            statement.executeUpdate("INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value) " +
                "VALUES(700,'Local Dispatch','TALKGROUP','APCO25',1001)");
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO recording_system(id,system_key) VALUES(1,'p25:00001:001')");
            statement.executeUpdate("UPDATE recording_call SET alias_list_id=700,system_id=1,call_type=2 WHERE id=1");
            statement.executeUpdate("INSERT INTO recording_patch_member(call_id,kind,local_id,home_wacn," +
                "home_system,home_id,start_ms) VALUES(1,1,1001,2,2,1001,1000),(1,1,0,2,2,1001,1000)");
        }
        String base = ManagedRecordingsHttpController.BROWSE_PATH;
        HttpResponse<String> foreign = send(request(base + "/calls?from_ms=0&to_ms=5000&q=Local").GET());
        assertEquals(200, foreign.statusCode(), foreign.body());
        assertEquals(0, json(foreign).at("/data/calls").size());
        JsonNode detail = json(send(request(base + "/calls/1").GET())).at("/data/patch_members");
        assertEquals(2, detail.size());
        assertTrue(detail.findValues("alias").isEmpty());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE recording_patch_member SET home_wacn=1,home_system=1");
        }
        mBrowseClock.addAndGet(TimeUnit.SECONDS.toNanos(2)); // Direct fixture SQL bypasses API invalidation.
        HttpResponse<String> local = send(request(base + "/calls?from_ms=0&to_ms=5000&q=Local").GET());
        assertEquals(200, local.statusCode(), local.body());
        assertEquals(1, json(local).at("/data/calls").size());
        detail = json(send(request(base + "/calls/1").GET())).at("/data/patch_members");
        assertEquals(2, detail.findValues("alias").size());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM recording_patch_member WHERE local_id<>0");
        }
        mBrowseClock.addAndGet(TimeUnit.SECONDS.toNanos(2)); // Direct fixture SQL bypasses API invalidation.
        local = send(request(base + "/calls?from_ms=0&to_ms=5000&q=Local").GET());
        assertEquals(200, local.statusCode(), local.body());
        assertEquals(1, json(local).at("/data/calls").size(), "A same-home zero-address member is searchable by its Alias");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE recording_patch_member SET home_wacn=2,home_system=2");
        }
        mBrowseClock.addAndGet(TimeUnit.SECONDS.toNanos(2)); // Direct fixture SQL bypasses API invalidation.
        foreign = send(request(base + "/calls?from_ms=0&to_ms=5000&q=Local").GET());
        assertEquals(200, foreign.statusCode(), foreign.body());
        assertEquals(0, json(foreign).at("/data/calls").size());
    }

    @Test
    void patchAliasLookupBudgetCountsDistinctMissesAcrossTheWholePageAndReturnsAnActionableError() throws Exception
    {
        try(Connection connection = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(700,'Fixture Members','P25')");
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE recording_call SET alias_list_id=700,call_type=2 WHERE id=1");
            statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,alias_list_id,protocol,call_type,voice_type,target_id) " +
                "VALUES(2,2000,3000,1000,'other.mp3',5,700,1,2,1,4001)");
            statement.executeUpdate("WITH RECURSIVE members(id) AS (VALUES(1000) UNION ALL " +
                "SELECT id+1 FROM members WHERE id<5095) " +
                "INSERT INTO recording_patch_member(call_id,kind,local_id,home_wacn,home_system,home_id,start_ms) " +
                "SELECT 1,1,id,-1,-1,-1,1000 FROM members");
            statement.executeUpdate("INSERT INTO recording_patch_member " +
                "SELECT 2,kind,local_id,home_wacn,home_system,home_id,2000 FROM recording_patch_member WHERE call_id=1");
        }
        String base = ManagedRecordingsHttpController.BROWSE_PATH + "/calls?from_ms=0&to_ms=5000";
        HttpResponse<String> boundary = send(request(base).GET());
        assertEquals(200, boundary.statusCode(), boundary.body());
        JsonNode calls = json(boundary).at("/data/calls");
        assertEquals(2, calls.size());
        assertEquals(4096, calls.get(0).get("patch_members").size());
        assertEquals(4096, calls.get(1).get("patch_members").size(), "Duplicate misses share the same lookup budget");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO recording_patch_member VALUES(1,1,6000,-1,-1,-1,1000)");
        }
        mBrowseClock.addAndGet(TimeUnit.SECONDS.toNanos(2)); // Direct fixture SQL bypasses API invalidation.
        HttpResponse<String> overflow = send(request(base).GET());
        assertEquals(422, overflow.statusCode(), overflow.body());
        assertEquals("recording_alias_lookup_too_broad", json(overflow).at("/error/code").textValue());
        assertTrue(json(overflow).at("/error/message").textValue().contains("Choose a narrower time range or fewer recordings"));
    }

    @Test
    void radioOnlyNamesSkipZeroAddressGroupCandidatesAndHomeDirectoryFanoutWhileMixedNamesKeepBothKinds() throws Exception
    {
        try(Connection connection = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(700,'Fixture Names','P25')");
            statement.executeUpdate("INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value) " +
                "VALUES(700,'Patrol radio','RADIO_ID','APCO25',101)");
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO recording_system(id,system_key) VALUES(1,'p25:00001:001')");
            statement.executeUpdate("UPDATE recording_call SET alias_list_id=700,system_id=1 WHERE id=1");
            statement.executeUpdate("WITH RECURSIVE n(x) AS(VALUES(2) UNION ALL SELECT x+1 FROM n WHERE x<7) " +
                "INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes,system_id," +
                "alias_list_id,protocol,call_type,voice_type,source_id,target_id) " +
                "SELECT x,x*1000,x*1000+1000,1000,'group-'||x,5,1,700,1,2,1,999,4001 FROM n");
            statement.executeUpdate("INSERT INTO recording_patch_member " +
                "SELECT id,1,0,1,1,101,start_ms FROM recording_call WHERE id>1");
        }
        String base = ManagedRecordingsHttpController.BROWSE_PATH + "/calls?from_ms=0&to_ms=10000&q=Patrol";
        HttpResponse<String> radio = send(request(base + "&limit=1").GET());
        assertEquals(200, radio.statusCode(), radio.body());
        assertEquals(1, json(radio).at("/data/calls/0/id").longValue(),
            "False group candidates must not consume the five empty candidate pages before the radio call");
        try(Connection connection = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value) " +
                "VALUES(700,'Patrol group','TALKGROUP','APCO25',101)");
        }
        HttpResponse<String> mixed = send(request(base).GET());
        assertEquals(200, mixed.statusCode(), mixed.body());
        assertEquals(7, json(mixed).at("/data/calls").size(), "Both matching name kinds survive deduplication");
        try(Connection connection = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE alias SET name='Different group' WHERE matcher_type='TALKGROUP'");
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("WITH RECURSIVE n(x) AS(VALUES(2) UNION ALL SELECT x+1 FROM n WHERE x<129) " +
                "INSERT INTO recording_system(system_key) SELECT printf('p25:%05x:001',x) FROM n");
        }
        radio = send(request(base + "&limit=1").GET());
        assertEquals(200, radio.statusCode(), radio.body());
        assertEquals(1, json(radio).at("/data/calls/0/id").longValue(), "Radio-only names do not inspect the group-home directory");
    }

    @Test
    void otaNameSearchRetainsHomeOwnershipDifferentWorkingIdsAndPaging() throws Exception
    {
        String system = "p25:00001:001";
        int radio = 10_900_077;
        try(Connection connection = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code," +
                "p25_wacn,p25_system_id,first_seen_ms,last_seen_ms) VALUES(501,'" + system +
                "',1,0,1,1,1000,2000)");
            statement.executeUpdate("INSERT INTO radio_system_identity_summary(radio_system_id,identity_kind_code," +
                "home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms,last_talker_alias," +
                "last_talker_alias_seen_ms) VALUES(501,2,1,1," + radio +
                ",1000,2000,'Home OTA',2000),(501,2,2,2," + radio +
                ",1000,2000,'Foreign OTA',2000),(501,2,-1,-1," + radio +
                ",1000,2000,'Legacy OTA',2000)");
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO recording_system(id,system_key) VALUES(501,'" + system +
                "'),(502,'p25:00002:002')");
            statement.executeUpdate("UPDATE recording_call SET system_id=501,source_id=" + radio + " WHERE id=1");
            statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,system_id,protocol,call_type,voice_type,source_id,source_home_wacn," +
                "source_home_system,source_home_id,target_id) VALUES" +
                "(2,2000,3000,1000,'working.mp3',5,501,1,1,1,555,1,1," + radio + ",1001)," +
                "(3,2000,3000,1000,'foreign.mp3',5,501,1,1,1," + radio + ",2,2," + radio + ",1001)," +
                "(4,2000,3000,1000,'native.mp3',5,501,1,1,1," + radio + ",1,1," + radio + ",1001)," +
                "(5,2000,3000,1000,'other-system.mp3',5,502,1,1,1," + radio + ",1,1," + radio + ",1001)");
            statement.executeUpdate("INSERT INTO recording_site(id,wacn,system_id,rfss,site_id) VALUES(501,1,1,1,1)");
            statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,protocol,call_type,voice_type,source_id,source_home_wacn,source_home_system," +
                "source_home_id,target_id,winner_site_id) VALUES" +
                "(6,2000,3000,1000,'legacy-site.mp3',5,1,1,1,555,1,1," + radio + ",1001,501)");
        }
        String base = ManagedRecordingsHttpController.BROWSE_PATH + "/calls?from_ms=0&to_ms=5000";
        HttpResponse<String> response = send(request(base + "&q=Home%20OTA&limit=1").GET());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(6L, json(response).at("/data/calls/0/id").longValue());
        String cursor = json(response).at("/data/next_cursor").textValue();
        response = send(request(base + "&q=Home%20OTA&limit=1&cursor=" + cursor).GET());
        assertEquals(4L, json(response).at("/data/calls/0/id").longValue());
        cursor = json(response).at("/data/next_cursor").textValue();
        response = send(request(base + "&q=Home%20OTA&limit=1&cursor=" + cursor).GET());
        assertEquals(2L, json(response).at("/data/calls/0/id").longValue());
        assertFalse(json(response).at("/data").has("next_cursor"));
        assertEquals(3L, json(send(request(base + "&q=Foreign%20OTA").GET()))
            .at("/data/calls/0/id").longValue());
        assertEquals(1L, json(send(request(base + "&q=Legacy%20OTA").GET()))
            .at("/data/calls/0/id").longValue());
        assertEquals(3, json(send(request(base + "&system_key=" + system + "&q=" + radio).GET()))
            .at("/data/calls").size(), "numeric search must retain its raw-address behavior");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,system_id,protocol,call_type,voice_type,source_id,target_id," +
                "target_home_wacn,target_home_system,target_home_id) VALUES" +
                "(8,2000,3000,1000,'direct.mp3',5,501,1,3,1,999,555,1,1," + radio + ")," +
                "(9,2000,3000,1000,'patch.mp3',5,501,1,2,1,999,1002,NULL,NULL,NULL)");
            statement.executeUpdate("INSERT INTO recording_patch_member(call_id,kind,local_id,home_wacn," +
                "home_system,home_id,start_ms) VALUES(9,2,777,1,1," + radio + ",2000)");
        }
        mBrowseClock.addAndGet(TimeUnit.SECONDS.toNanos(2)); // Direct fixture SQL bypasses API invalidation.
        response = send(request(base + "&q=Home%20OTA").GET());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(5, json(response).at("/data/calls").size());
        assertEquals("Home OTA", json(response).at("/data/calls/0/patch_members/0/ota_alias").textValue());
        assertEquals("Home OTA", json(response).at("/data/calls/1/destination_radio_ota_alias").textValue());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,system_id,protocol,call_type,voice_type,source_id,source_home_wacn," +
                "source_home_system,source_home_id,target_id) VALUES" +
                "(10,2000,3000,1000,'canonical-only.mp3',5,501,1,1,1,NULL,1,1," + radio + ",1001)");
        }
        mBrowseClock.addAndGet(TimeUnit.SECONDS.toNanos(2)); // Direct fixture SQL bypasses API invalidation.
        response = send(request(base + "&q=Home%20OTA").GET());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(6, json(response).at("/data/calls").size());
        assertEquals(10L, json(response).at("/data/calls/0/id").longValue());
        assertEquals("Home OTA", json(response).at("/data/calls/0/source_ota_alias").textValue());
        assertTrue(json(response).at("/data/calls/0/source_entity_ref").isObject());
        assertFalse(json(response).at("/data/calls/0").has("source_id"));
    }

    @Test
    void chosenAliasAndBroadRangeKeepCurrentLabelScopeAcrossSystems() throws Exception
    {
        Path mainDatabase = mDirectory.resolve("main.sqlite");
        try(Connection connection = StatsPairedTestDatabase.open(mainDatabase);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES " +
                "(700,'Fire List','P25'),(701,'Police List','P25')");
            statement.executeUpdate("INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value) " +
                "VALUES(700,'Fire Dispatch','TALKGROUP','APCO25',4001)");
            statement.executeUpdate("INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value) " +
                "VALUES(701,'Police Dispatch','TALKGROUP','APCO25',4001)");
            statement.executeUpdate("INSERT INTO alias(alias_list_id,name,matcher_type,protocol,min_value,max_value) " +
                "VALUES(700,'Fire Zone','TALKGROUP_RANGE','APCO25',4000,4500)");
        }
        Path catalogDatabase = mDirectory.resolve("managed-recordings.sqlite");
        Files.write(mDirectory.resolve("recordings-managed/two.mp3"), new byte[]{'I', 'D', '3', 'a', 'b'});
        Files.write(mDirectory.resolve("recordings-managed/three.mp3"), new byte[]{'I', 'D', '3', 'c', 'd'});
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + catalogDatabase);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE recording_call SET alias_list_id=700 WHERE id=1");
            statement.executeUpdate("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,alias_list_id,protocol,call_type,voice_type,source_id,target_id) " +
                "VALUES(1500,2500,1000,'two.mp3',5,701,1,1,1,102,4001)");
            statement.executeUpdate("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,alias_list_id,protocol,call_type,voice_type,source_id,target_id) " +
                "VALUES(1700,2700,1000,'three.mp3',5,700,1,1,1,103,4005)");
            statement.executeUpdate("UPDATE catalog_metadata SET call_count=3,total_bytes=15 WHERE id=1");
        }

        String calls = ManagedRecordingsHttpController.BROWSE_PATH + "/calls?from_ms=0&to_ms=5000";
        HttpResponse<String> exact = send(request(calls + "&talkgroup_id=4001&q=Fire%20Dispatch").GET());
        assertEquals(200, exact.statusCode(), exact.body());
        assertEquals(1, json(exact).at("/data/calls").size());
        assertEquals(1, json(exact).at("/data/calls/0/id").longValue());

        HttpResponse<String> range = send(request(calls +
            "&talkgroup_min=4000&talkgroup_max=4500&q=Fire%20Zone").GET());
        assertEquals(200, range.statusCode(), range.body());
        assertEquals(1, json(range).at("/data/calls").size());
        assertEquals(3, json(range).at("/data/calls/0/id").longValue());
    }

    @Test
    void exactRadioKeyKeepsReceivingSystemAndOtherQueryConstraints() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDirectory.resolve(
            "managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO recording_system(id,system_key) VALUES(501,'p25:00001:001')");
            statement.executeUpdate("UPDATE recording_call SET system_id=501 WHERE id=1");
            statement.executeUpdate("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,system_id,protocol,call_type,voice_type,source_id,target_id,source_home_wacn," +
                "source_home_system,source_home_id) " +
                "VALUES(1500,2500,1000,'foreign.mp3',5,501,1,1,1,101,4001,2,2,777)");
            statement.executeUpdate("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,system_id,protocol,call_type,voice_type,source_id,target_id,source_home_wacn," +
                "source_home_system,source_home_id) " +
                "VALUES(1700,2700,1000,'working.mp3',5,501,1,1,1,555,4002,2,2,777)");
            statement.executeUpdate("UPDATE catalog_metadata SET call_count=3,total_bytes=15 WHERE id=1");
        }
        String base = ManagedRecordingsHttpController.BROWSE_PATH +
            "/calls?from_ms=0&to_ms=5000&system_key=p25:00001:001";
        HttpResponse<String> local = send(request(base + "&radio_identity_key=v1-r-00001-001-101").GET());
        assertEquals(200, local.statusCode(), local.body());
        assertEquals(1, json(local).at("/data/calls").size());
        assertEquals(1, json(local).at("/data/calls/0/id").longValue());
        String foreign = base + "&radio_identity_key=v1-r-00002-002-777";
        HttpResponse<String> everyWorkingId = send(request(foreign).GET());
        assertEquals(200, everyWorkingId.statusCode(), everyWorkingId.body());
        assertEquals(2, json(everyWorkingId).at("/data/calls").size());
        HttpResponse<String> constrained = send(request(foreign + "&talkgroup_id=4001&q=4001").GET());
        assertEquals(200, constrained.statusCode(), constrained.body());
        assertEquals(1, json(constrained).at("/data/calls").size());
        assertEquals(2, json(constrained).at("/data/calls/0/id").longValue());
        assertEquals(0, json(send(request(foreign + "&q=unmatched-name").GET())).at("/data/calls").size());
        for(String invalid: new String[]{"bad", "v1-g-00001-001-101", "v1-r-x-x-101",
            "v1-r-00001-001-16777215"})
        {
            HttpResponse<String> response = send(request(base + "&radio_identity_key=" + invalid).GET());
            assertEquals(400, response.statusCode(), response.body());
            assertEquals("radio_identity_key", json(response).at("/error/field").textValue());
        }
        for(String scope: new String[]{"", "&system_key=channel:cccccccc-cccc-cccc-cccc-cccccccccccc",
            "&system_key=p25:INVALID"})
        {
            HttpResponse<String> response = send(request(ManagedRecordingsHttpController.BROWSE_PATH +
                "/calls?radio_identity_key=v1-r-00001-001-101" + scope).GET());
            assertEquals(400, response.statusCode(), response.body());
        }
    }

    @Test
    void exactGroupKeyRetainsHomeTupleAndReceivingScopeWithoutRadioRoleMatches() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDirectory.resolve(
            "managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO recording_system(id,system_key) VALUES(501,'p25:00001:001')");
            statement.executeUpdate("UPDATE recording_call SET system_id=501 WHERE id=1");
            statement.executeUpdate("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,system_id,protocol,call_type,voice_type,source_id,target_id,target_home_wacn," +
                "target_home_system,target_home_id) " +
                "VALUES(1500,2500,1000,'foreign-group.mp3',5,501,1,1,1,101,3101,2,2,501)");
            statement.executeUpdate("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,system_id,protocol,call_type,voice_type,source_id,target_id,target_home_wacn," +
                "target_home_system,target_home_id) " +
                "VALUES(1700,2700,1000,'direct-radio.mp3',5,501,1,3,1,101,3101,2,2,501)");
            statement.executeUpdate("UPDATE catalog_metadata SET call_count=3,total_bytes=15 WHERE id=1");
        }
        String base = ManagedRecordingsHttpController.BROWSE_PATH +
            "/calls?from_ms=0&to_ms=5000&system_key=p25:00001:001";
        for(String kind: List.of("g", "p"))
        {
            var response = send(request(base + "&talkgroup_identity_key=v1-" + kind + "-00002-002-501").GET());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(1, json(response).at("/data/calls").size());
            assertEquals(3101, json(response).at("/data/calls/0/target_observed_local_id").intValue());
            assertEquals(501, json(response).at("/data/calls/0/target_canonical_group_identity/talkgroup_id").intValue());
        }
        for(String key: List.of("bad", "v1-r-00002-002-501", "v1-g-x-x-501"))
        {
            var response = send(request(base + "&talkgroup_identity_key=" + key).GET());
            assertEquals(400, response.statusCode(), response.body());
            assertEquals("talkgroup_identity_key", json(response).at("/error/field").textValue());
        }
        var unknownScope = send(request(ManagedRecordingsHttpController.BROWSE_PATH +
            "/calls?talkgroup_identity_key=v1-g-00002-002-501").GET());
        assertEquals(400, unknownScope.statusCode());
    }

    @Test
    void focusedDiscoveryAndStructuredFiltersKeepStableSelectionsAndRejectInvalidValues() throws Exception
    {
        String browse = ManagedRecordingsHttpController.BROWSE_PATH;
        HttpResponse<String> discovered = send(request(browse + "/identities?kind=talkgroup&from_ms=0&to_ms=5000").GET());
        assertEquals(200, discovered.statusCode(), discovered.body());
        JsonNode row = json(discovered).at("/data/rows/0");
        assertEquals(4001, row.path("id").intValue());
        assertEquals("4001", row.path("label").textValue());
        assertTrue(row.path("selection").has("system_key"));
        assertTrue(row.at("/selection/system_key").isNull());
        assertEquals(1, row.path("call_count").intValue());
        assertFalse(json(discovered).at("/data/has_more").booleanValue());
        String selected = java.net.URLEncoder.encode(row.path("selection").toString(), java.nio.charset.StandardCharsets.UTF_8);
        String array = java.net.URLEncoder.encode("[" + row.path("selection") + "]", java.nio.charset.StandardCharsets.UTF_8);
        HttpResponse<String> calls = send(request(browse + "/calls?from_ms=0&to_ms=5000&talkgroups=" + array).GET());
        assertEquals(200, calls.statusCode(), calls.body());
        assertEquals(1, json(calls).at("/data/calls").size());
        assertEquals(400, send(request(browse + "/calls?talkgroups=" + selected).GET()).statusCode());
        String missingSystem = java.net.URLEncoder.encode("[{\"id\":4001}]", java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(400, send(request(browse + "/calls?talkgroups=" + missingSystem).GET()).statusCode());
        assertEquals(400, send(request(browse + "/calls?radios=%5B%5D&radio_role=recipient").GET()).statusCode());
        assertEquals(400, send(request(browse + "/calls?tone_frequency_hz=NaN").GET()).statusCode());
        assertEquals(400, send(request(browse + "/calls?tone_then_hz=600").GET()).statusCode());
        assertEquals(400, send(request(browse + "/calls?paging_tones=none").GET()).statusCode());
        assertEquals(400, send(request(browse + "/identities?kind=site").GET()).statusCode());
        assertEquals(400, send(request(browse + "/identities?kind=radio&limit=101").GET()).statusCode());
        assertEquals(400, send(request(browse + "/identities?kind=radio&from_ms=5000&to_ms=0").GET()).statusCode());
        assertEquals(0, json(send(request(browse + "/identities?kind=talkgroup&from_ms=3000&to_ms=5000").GET()))
            .at("/data/rows").size());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE recording_call SET source_home_wacn=1,source_home_system=1," +
                "source_home_id=101 WHERE id=1");
        }
        JsonNode qualifiedUnknown = json(send(request(browse + "/identities?kind=radio&radio_role=source&from_ms=0&to_ms=5000").GET()))
            .at("/data/rows/0/selection");
        assertTrue(qualifiedUnknown.path("system_key").isNull());
        assertEquals("v1-r-00001-001-101", qualifiedUnknown.path("identity_key").textValue());
        String qualified = java.net.URLEncoder.encode("[" + qualifiedUnknown + "]", java.nio.charset.StandardCharsets.UTF_8);
        HttpResponse<String> unknownCalls = send(request(browse +
            "/calls?from_ms=0&to_ms=5000&radio_role=source&radios=" + qualified).GET());
        assertEquals(200, unknownCalls.statusCode(), unknownCalls.body());
        assertEquals(1, json(unknownCalls).at("/data/calls").size());
    }

    @Test
    void discoveryAndIdentitySuggestionsShareTheBoundedRecordingPeriod() throws Exception
    {
        String browse = ManagedRecordingsHttpController.BROWSE_PATH;
        assertEquals(0, json(send(request(browse + "/identities?kind=talkgroup").GET())).at("/data/rows").size());
        assertEquals(0, json(send(request(browse + "/suggestions?kind=talkgroup&q=4001").GET())).at("/data").size());
        assertEquals(1, json(send(request(browse + "/suggestions?kind=talkgroup&q=4001&from_ms=0&to_ms=5000").GET()))
            .at("/data").size());
        for(String endpoint: List.of("/identities?kind=talkgroup", "/suggestions?kind=talkgroup&q=4001"))
        {
            assertEquals(400, send(request(browse + endpoint + "&from_ms=0&to_ms=9223372036854775807").GET()).statusCode());
            assertEquals(400, send(request(browse + endpoint + "&from_ms=5000&to_ms=1000").GET()).statusCode());
        }
    }

    @Test
    void populatedBrowserEndpointsValidateSortScopeAndTypedSelectionBounds() throws Exception
    {
        String browse=ManagedRecordingsHttpController.BROWSE_PATH;
        for(String kind: List.of("system","site","channel"))
        {
            HttpResponse<String> response=send(request(browse+"/entities?kind="+kind+"&from_ms=0&to_ms=5000&sort=calls").GET());
            assertEquals(200,response.statusCode(),response.body());
            assertTrue(json(response).at("/data/rows").isArray());
            assertEquals(400,send(request(browse+"/entities?kind="+kind+"&sort=invalid").GET()).statusCode());
        }
        for(String order: List.of("calls","name","recent"))
            assertEquals(200,send(request(browse+"/identities?kind=talkgroup&from_ms=0&to_ms=5000&sort="+order).GET()).statusCode());
        assertEquals(400,send(request(browse+"/identities?kind=talkgroup&sort=invalid").GET()).statusCode());
        assertEquals(400,send(request(browse+"/entities?kind=radio").GET()).statusCode());
        assertEquals(400,send(request(browse+"/entities?kind=channel&system_key=invalid").GET()).statusCode());
        assertEquals(400,send(request(browse+"/entities?kind=channel&from_ms=0&to_ms=9223372036854775807").GET()).statusCode());
        List<Map<String,Object>> choices=new java.util.ArrayList<>();
        String channelScope="nxdn-c:channel:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        for(int index=0;index<100;index++)
        {
            Map<String,Object> choice=new java.util.LinkedHashMap<>();
            choice.put("id",null); choice.put("system_key",channelScope); choice.put("protocol","TAIT1200");
            choice.put("value",String.format("%03d",index)+"漢\\\"".repeat(41)+"ab");
            choices.add(choice);
        }
        String raw=MAPPER.writeValueAsString(choices);
        assertTrue(raw.length()>16384);
        String encoded=java.net.URLEncoder.encode(raw,java.nio.charset.StandardCharsets.UTF_8);
        HttpResponse<String> valid=send(request(browse+"/calls?from_ms=0&to_ms=5000&radio_role=source&radios="+encoded).GET());
        assertEquals(200,valid.statusCode(),valid.body());
        assertEquals(0,json(valid).at("/data/calls").size());
        String bad=java.net.URLEncoder.encode("[{\"id\":null,\"system_key\":null,\"protocol\":\"ARS\",\"value\":\"UNIT-A\"}]",
            java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(400,send(request(browse+"/calls?radios="+bad).GET()).statusCode());
        assertEquals(400,send(request(browse+"/calls?talkgroups="+encoded).GET()).statusCode());
        choices.add(choices.getFirst());
        String tooMany=java.net.URLEncoder.encode(MAPPER.writeValueAsString(choices),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(400,send(request(browse+"/calls?radios="+tooMany).GET()).statusCode());
    }

    @Test
    void numericAutocompleteReturnsUnaliasedTalkgroupsInSpecificAndGeneralFinders() throws Exception
    {
        String base = ManagedRecordingsHttpController.BROWSE_PATH + "/suggestions?from_ms=0&to_ms=5000&q=4001";
        for(String suffix: new String[]{"&kind=talkgroup", ""})
        {
            HttpResponse<String> response = send(request(base + suffix).GET());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode rows = json(response).at("/data");
            assertEquals(1, rows.size());
            assertEquals("talkgroup", rows.get(0).path("kind").textValue());
            assertEquals("4001", rows.get(0).path("id").textValue());
            assertEquals("Talkgroup 4001", rows.get(0).path("label").textValue());
            assertFalse(rows.get(0).has("alias_list_id"));
        }
        assertEquals("4001", json(send(request(ManagedRecordingsHttpController.BROWSE_PATH +
            "/suggestions?from_ms=0&to_ms=5000&q=40&kind=talkgroup").GET())).at("/data/0/id").textValue());
        assertEquals("4001", json(send(request(ManagedRecordingsHttpController.BROWSE_PATH +
            "/suggestions?from_ms=0&to_ms=5000&q=4&kind=talkgroup").GET())).at("/data/0/id").textValue());
        assertEquals(0, json(send(request(ManagedRecordingsHttpController.BROWSE_PATH +
            "/suggestions?from_ms=0&to_ms=5000&q=4009&kind=talkgroup").GET())).at("/data").size());
        assertEquals(0, json(send(request(base + "&kind=talkgroup&system_key=other-system").GET()))
            .at("/data").size());

        try(Connection connection = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(700,'Fixture List','P25')");
            statement.executeUpdate("INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value) " +
                "VALUES(700,'Fixture Dispatch','TALKGROUP','APCO25',4001)");
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE recording_call SET alias_list_id=700 WHERE id=1");
        }
        mBrowseClock.addAndGet(TimeUnit.SECONDS.toNanos(2)); // Direct fixture SQL bypasses API invalidation.
        JsonNode rows = json(send(request(base + "&kind=talkgroup").GET())).at("/data");
        assertEquals(1, rows.size());
        assertEquals("Fixture Dispatch", rows.get(0).path("label").textValue());
    }

    @Test
    void exactUnaliasedNumericTalkgroupPrecedesFullPagesOfRecordedSubstringMatches() throws Exception
    {
        populateNumericSubstringSuggestions();
        String base = ManagedRecordingsHttpController.BROWSE_PATH + "/suggestions?from_ms=0&to_ms=5000&q=4001";
        for(String kind: new String[]{"&kind=talkgroup", ""})
        {
            for(int limit: new int[]{20, 1})
            {
                HttpResponse<String> response = send(request(base + kind + "&limit=" + limit).GET());
                assertEquals(200, response.statusCode(), response.body());
                JsonNode rows = json(response).at("/data");
                assertEquals(limit, rows.size());
                assertEquals("talkgroup", rows.get(0).path("kind").textValue());
                assertEquals("4001", rows.get(0).path("id").textValue());
                assertEquals("Talkgroup 4001", rows.get(0).path("label").textValue());
                assertFalse(rows.get(0).has("alias_list_id"));
                assertEquals(1, exactCount(rows, "talkgroup", "4001"));
            }
        }
    }

    @Test
    void exactFriendlyNamesRemainCurrentBeyondSubstringAliasesAndGeneralChannelMatches() throws Exception
    {
        populateNumericSubstringSuggestions();
        try(Connection connection = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value) " +
                "VALUES(700,'Z Exact Dispatch','TALKGROUP','APCO25',4001)," +
                "(700,'Z Exact Radio','RADIO_ID','APCO25',4001)");
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE recording_call SET alias_list_id=700 WHERE id=1");
            statement.executeUpdate("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,alias_list_id,protocol,call_type,voice_type,source_id,target_id) " +
                "VALUES(2000,3000,1000,'direct-exact.mp3',5,700,1,3,1,999,4001)," +
                "(2100,3100,1000,'direct-only.mp3',5,700,1,3,1,998,4002)");
        }
        String base = ManagedRecordingsHttpController.BROWSE_PATH + "/suggestions?from_ms=0&to_ms=5000&q=4001";
        for(String kind: new String[]{"&kind=talkgroup", ""})
        {
            for(int limit: new int[]{20, 1})
            {
                JsonNode rows = json(send(request(base + kind + "&limit=" + limit).GET())).at("/data");
                assertEquals("talkgroup", rows.get(0).path("kind").textValue());
                assertEquals("4001", rows.get(0).path("id").textValue());
                assertEquals("Z Exact Dispatch", rows.get(0).path("label").textValue());
                assertEquals(700, rows.get(0).path("alias_list_id").intValue());
                assertEquals(1, exactCount(rows, "talkgroup", "4001"));
                if(kind.isEmpty() && limit == 20)
                {
                    assertEquals("radio", rows.get(1).path("kind").textValue());
                    assertEquals("4001", rows.get(1).path("id").textValue());
                    assertEquals("Z Exact Radio", rows.get(1).path("label").textValue());
                    assertEquals(1, exactCount(rows, "radio", "4001"));
                }
            }
        }
        JsonNode directOnly = json(send(request(ManagedRecordingsHttpController.BROWSE_PATH +
            "/suggestions?from_ms=0&to_ms=5000&q=4002").GET())).at("/data");
        assertEquals(1, directOnly.size());
        assertEquals("radio", directOnly.get(0).path("kind").textValue());
        assertEquals("4002", directOnly.get(0).path("id").textValue());
        assertEquals(0, json(send(request(ManagedRecordingsHttpController.BROWSE_PATH +
            "/suggestions?from_ms=0&to_ms=5000&q=4002&kind=talkgroup").GET())).at("/data").size());

        try(Connection connection = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE alias SET name='Z Current Dispatch' " +
                "WHERE matcher_type='TALKGROUP' AND value=4001");
        }
        mBrowseClock.addAndGet(TimeUnit.SECONDS.toNanos(2)); // Direct fixture SQL bypasses API invalidation.
        assertEquals("Z Current Dispatch", json(send(request(base + "&limit=1").GET()))
            .at("/data/0/label").textValue());
    }

    private void populateNumericSubstringSuggestions() throws Exception
    {
        try(Connection main = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite"));
            Statement names = main.createStatement();
            Connection catalog = DriverManager.getConnection("jdbc:sqlite:" +
                mDirectory.resolve("managed-recordings.sqlite")); Statement calls = catalog.createStatement())
        {
            names.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(700,'Fixture List','P25')");
            for(int index = 0; index < 25; index++)
            {
                int identity = 14001 + 100000 * index;
                String prefix = "A" + String.format("%02d", index) + " 4001";
                String channel = String.format("00000000-0000-0000-0000-%012d", index + 1);
                names.executeUpdate("INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value) " +
                    "VALUES(700,'" + prefix + " Dispatch','TALKGROUP','APCO25'," + identity + ")");
                names.executeUpdate("INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order," +
                    "name,alias_list_id,decoder_type,primary_frequency_hz,config_json) VALUES('" + channel +
                    "','TRUNKED'," + index + ",'" + prefix + " Channel',700,'P25_PHASE1',853000000,'{}')");
                calls.executeUpdate("INSERT INTO recording_channel(id,channel_uuid) VALUES(" + (index + 100) +
                    ",'" + channel + "')");
                calls.executeUpdate("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path," +
                    "size_bytes,channel_id,alias_list_id,protocol,call_type,voice_type,source_id,target_id) VALUES(" +
                    "1500,2500,1000,'substring-" + index + ".mp3',5," + (index + 100) +
                    ",700,1,1,1,101," + identity + ")");
            }
        }
    }

    private static long exactCount(JsonNode rows, String kind, String id)
    {
        long count = 0;
        for(JsonNode row: rows)
        {
            if(kind.equals(row.path("kind").textValue()) && id.equals(row.path("id").textValue())) count++;
        }
        return count;
    }

    @Test
    void autocompleteOnlyReturnsNamesWithCatalogCallsAndFiltersBeforeItsLimit() throws Exception
    {
        try(Connection connection = StatsPairedTestDatabase.open(mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) " +
                "VALUES(700,'Fixture Dispatch List','P25')");
            for(int index = 0; index < 25; index++)
            {
                statement.executeUpdate("INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value) " +
                    "VALUES(700,'A" + String.format("%02d", index) + " Dispatch Unused'," +
                    "'TALKGROUP','APCO25'," + (5000 + index) + ")");
            }
            statement.executeUpdate("INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value) " +
                "VALUES(700,'Z Dispatch Recorded','TALKGROUP','APCO25',4001)");
        }
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE recording_call SET alias_list_id=700 WHERE id=1");
        }

        String path = ManagedRecordingsHttpController.BROWSE_PATH +
            "/suggestions?from_ms=0&to_ms=5000&q=Dispatch&kind=talkgroup&limit=1";
        HttpResponse<String> response = send(request(path).GET());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode rows = json(response).at("/data");
        assertEquals(1, rows.size());
        assertEquals("Z Dispatch Recorded", rows.get(0).path("label").textValue());
        assertEquals("4001", rows.get(0).path("id").textValue());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("managed-recordings.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM recording_call WHERE id=1");
        }
        mBrowseClock.addAndGet(TimeUnit.SECONDS.toNanos(2)); // Direct fixture SQL bypasses API invalidation.
        assertEquals(0, json(send(request(path).GET())).at("/data").size());
    }

    @Test
    void groupedDiscoveryUsesIndependentTenChoicePagesAndRecordedDcsSelections() throws Exception
    {
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+mDirectory.resolve("managed-recordings.sqlite"));
            var statement=connection.createStatement())
        {
            statement.execute("UPDATE recording_call SET tone_kind=1,tone='023' WHERE id=1");
            for(int id=4100;id<4125;id++)
                statement.execute("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path,size_bytes,"+
                    "protocol,call_type,voice_type,target_id) VALUES(1000,2000,1000,'"+id+".mp3',5,1,1,1,"+id+")");
        }
        String base=ManagedRecordingsHttpController.BROWSE_PATH;
        String discovery=base+"/identities?kind=talkgroup&grouped=true&from_ms=0&to_ms=5000&limit=100";
        var first=send(request(discovery).GET());
        assertEquals(200,first.statusCode(),first.body());
        JsonNode group=json(first).at("/data/groups/0");
        assertEquals(10,group.path("rows").size());
        assertEquals(26,group.path("match_count").intValue());
        assertTrue(group.path("has_more").booleanValue());
        String key=group.path("group_key").textValue();
        var second=send(request(discovery+"&group_key="+key+"&offset=10").GET());
        assertEquals(200,second.statusCode(),second.body());
        JsonNode next=json(second).at("/data/groups/0");
        assertEquals(key,next.path("group_key").textValue());
        assertEquals(10,next.path("rows").size());
        assertFalse(group.path("rows").get(0).path("id").equals(next.path("rows").get(0).path("id")));
        assertEquals(400,send(request(discovery+"&offset=10").GET()).statusCode());
        assertEquals(400,send(request(discovery+"&group_key=invalid").GET()).statusCode());
        var dcs=send(request(base+"/entities?kind=dcs&grouped=true&from_ms=0&to_ms=5000").GET());
        assertEquals(200,dcs.statusCode(),dcs.body());
        assertEquals("N023",json(dcs).at("/data/groups/0/rows/0/selection/dcs_code").textValue());
        assertEquals(1,json(send(request(base+"/calls?from_ms=0&to_ms=5000&dcs_code=N023").GET()))
            .at("/data/calls").size());
    }

    @Test
    void mixedTargetAndTextModesValidateBoundsAndRetainAnEffectivePeriod() throws Exception
    {
        long now=System.currentTimeMillis(), recent=now-3_600_000;
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+mDirectory.resolve("managed-recordings.sqlite"));
            var statement=connection.createStatement())
        {
            statement.execute("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes,"+
                "protocol,call_type,voice_type,target_id) VALUES(2,"+recent+","+(recent+12000)+
                ",12000,'recent.mp3',5,1,1,1,4001),(3,"+(recent+20000)+","+(recent+21000)+
                ",1000,'short.mp3',5,1,1,1,4001)");
            statement.execute("INSERT INTO recording_transcript(call_id,text,stored_at_ms) VALUES"+
                "(1,'fire dispatch',1000),(2,'fire dispatch',"+now+")");
        }
        String base=ManagedRecordingsHttpController.BROWSE_PATH+"/calls";
        var recentText=send(request(base+"?from_ms=0&to_ms=5000&transcript=fire").GET());
        assertEquals(200,recentText.statusCode(),recentText.body());
        assertEquals(2,json(recentText).at("/data/calls/0/id").intValue());
        long anchor=json(recentText).at("/data/to_ms").longValue();
        assertEquals(86_400_000L,anchor-json(recentText).at("/data/from_ms").longValue());
        var anchored=send(request(base+"?transcript=fire&to_ms="+anchor).GET());
        assertEquals(anchor,json(anchored).at("/data/to_ms").longValue(),"Paging retains its submitted recent anchor");
        assertEquals(1,json(send(request(base+"?from_ms=0&to_ms=5000&transcript=fire&text_range=time").GET()))
            .at("/data/calls/0/id").intValue());
        assertEquals(1,json(send(request(base+"?from_ms=0&to_ms=5000&has_transcript=1").GET()))
            .at("/data/calls/0/id").intValue(),"Presence alone does not override the selected period");
        assertEquals(1,json(send(request(base+"?from_ms="+(recent-1)+"&to_ms="+now+"&high_interest=1").GET()))
            .at("/data/calls").size());
        String targets=java.net.URLEncoder.encode("[{\"id\":4001,\"system_key\":null,\"protocol\":\"APCO25\"}]",
            java.nio.charset.StandardCharsets.UTF_8);
        String channels=java.net.URLEncoder.encode("[\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\"]",
            java.nio.charset.StandardCharsets.UTF_8);
        String mixed=base+"?from_ms=0&to_ms=5000&talkgroups="+targets+"&channel_ids="+channels;
        assertEquals(0,json(send(request(mixed).GET())).at("/data/calls").size());
        assertEquals(1,json(send(request(mixed+"&targets_any=true").GET())).at("/data/calls").size());
        assertEquals(0,json(send(request(mixed+"&targets_any=true&channel_id=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            .GET())).at("/data/calls").size(),"The legacy scalar channel remains an independent constraint");
        for(String invalid:List.of("channel_ids=%5B1%5D","targets_any=maybe","has_transcript=maybe",
            "text_range=all","dcs_code=N999","transcript=%22unterminated"))
            assertEquals(400,send(request(base+"?"+invalid).GET()).statusCode(),invalid);
    }

    private Session login() throws Exception
    {
        String body = MAPPER.writeValueAsString(Map.of("username", "admin", "password", PASSWORD));
        HttpResponse<String> response = send(request("/api/v1/auth/login")
            .header("Origin", mOrigin.toString()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(200, response.statusCode(), response.body());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        return new Session(cookie, json(response).at("/data/csrf_token").textValue());
    }

    private HttpRequest.Builder mutation(String path, Session session)
    {
        return request(path).header("Origin", mOrigin.toString()).header("Cookie", session.cookie())
            .header("X-CSRF-Token", session.csrf()).header("Content-Type", "application/json");
    }

    private HttpRequest.Builder request(String path)
    {
        return HttpRequest.newBuilder(mOrigin.resolve(path)).timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception
    {
        return mClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception
    {
        return MAPPER.readTree(response.body());
    }

    private record Session(String cookie, String csrf) {}
}
