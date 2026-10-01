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
            mPreferences, mSecurity, mMaintenance, new ManagedRecordingLabels(mainDatabase));
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
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
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
            "/suggestions?q=Fixture%20Radio%20System&kind=system").GET());
        assertEquals(200, suggestions.statusCode(), suggestions.body());
        JsonNode selected = json(suggestions).at("/data/0");
        assertEquals("Fixture Radio System", selected.path("label").textValue());
        JsonNode numeric = json(send(request(browse +
            "/suggestions?q=4001&kind=talkgroup&system_key=" + systemKey).GET())).at("/data");
        assertEquals(1, numeric.size());
        assertEquals("4001", numeric.get(0).path("id").textValue());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms," +
                "protocol,rfss,site) VALUES(91,1000,2000,'APCO25',1,1)");
        }
        JsonNode siteRows = json(send(request(browse + "/suggestions?q=1&kind=site&system_key=" +
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
        String search = browse + "/calls?from_ms=0&to_ms=5000&transcript=dispatch";
        JsonNode matches = json(send(request(search).GET())).at("/data/calls");
        assertEquals(1, matches.size());
        assertEquals("Dispatch test", matches.get(0).get("transcript_excerpt").textValue());
        assertEquals(0, json(send(request(browse +
            "/calls?from_ms=0&to_ms=5000&transcript=unmatched").GET())).at("/data/calls").size());
        assertEquals(400, send(request(browse + "/calls?transcript=" + "a".repeat(241)).GET()).statusCode());
    }

    @Test
    void chosenAliasAndBroadRangeKeepCurrentLabelScopeAcrossSystems() throws Exception
    {
        Path mainDatabase = mDirectory.resolve("main.sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mainDatabase);
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
    void numericAutocompleteReturnsUnaliasedTalkgroupsInSpecificAndGeneralFinders() throws Exception
    {
        String base = ManagedRecordingsHttpController.BROWSE_PATH + "/suggestions?q=4001";
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
            "/suggestions?q=40&kind=talkgroup").GET())).at("/data/0/id").textValue());
        assertEquals("4001", json(send(request(ManagedRecordingsHttpController.BROWSE_PATH +
            "/suggestions?q=4&kind=talkgroup").GET())).at("/data/0/id").textValue());
        assertEquals(0, json(send(request(ManagedRecordingsHttpController.BROWSE_PATH +
            "/suggestions?q=4009&kind=talkgroup").GET())).at("/data").size());
        assertEquals(0, json(send(request(base + "&kind=talkgroup&system_key=other-system").GET()))
            .at("/data").size());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
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
        JsonNode rows = json(send(request(base + "&kind=talkgroup").GET())).at("/data");
        assertEquals(1, rows.size());
        assertEquals("Fixture Dispatch", rows.get(0).path("label").textValue());
    }

    @Test
    void exactUnaliasedNumericTalkgroupPrecedesFullPagesOfRecordedSubstringMatches() throws Exception
    {
        populateNumericSubstringSuggestions();
        String base = ManagedRecordingsHttpController.BROWSE_PATH + "/suggestions?q=4001";
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
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
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
        String base = ManagedRecordingsHttpController.BROWSE_PATH + "/suggestions?q=4001";
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
            "/suggestions?q=4002").GET())).at("/data");
        assertEquals(1, directOnly.size());
        assertEquals("radio", directOnly.get(0).path("kind").textValue());
        assertEquals("4002", directOnly.get(0).path("id").textValue());
        assertEquals(0, json(send(request(ManagedRecordingsHttpController.BROWSE_PATH +
            "/suggestions?q=4002&kind=talkgroup").GET())).at("/data").size());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE alias SET name='Z Current Dispatch' " +
                "WHERE matcher_type='TALKGROUP' AND value=4001");
        }
        assertEquals("Z Current Dispatch", json(send(request(base + "&limit=1").GET()))
            .at("/data/0/label").textValue());
    }

    private void populateNumericSubstringSuggestions() throws Exception
    {
        try(Connection main = DriverManager.getConnection("jdbc:sqlite:" + mDirectory.resolve("main.sqlite"));
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
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mDirectory.resolve("main.sqlite")); Statement statement = connection.createStatement())
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
            "/suggestions?q=Dispatch&kind=talkgroup&limit=1";
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
        assertEquals(0, json(send(request(path).GET())).at("/data").size());
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
