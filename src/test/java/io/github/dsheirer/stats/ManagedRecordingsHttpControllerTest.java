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
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
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
        mPreferences.getRecordPreference().setRecordingMode(RecordingMode.CLASSIC);
        Path mainDatabase = mDirectory.resolve("main.sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mainDatabase))
        {
            SdrTrunkDatabaseSchema.create(connection);
            SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
        }
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
