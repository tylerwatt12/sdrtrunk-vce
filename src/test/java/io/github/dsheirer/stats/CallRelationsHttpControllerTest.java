/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.http.WebRequestSecurity;
import io.github.dsheirer.web.http.WebSessionHttpController;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CallRelationsHttpControllerTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    @Test
    void sourceAndEachTargetUseCurrentRequesterPermissionsAndNotificationTokensGrantNothing() throws Exception
    {
        try(var fixture = new Fixture(directory))
        {
            assertEquals(401, fixture.send("GET", "/activity/7", true).statusCode());
            assertEquals(0, fixture.activitySourceReads);
            fixture.access.setCapabilityTier("web-access", AccessTier.PUBLIC);
            fixture.access.setCapabilityTier("radio", AccessTier.ADMIN);
            fixture.access.setCapabilityTier("recordings", AccessTier.PUBLIC);
            assertEquals(401, fixture.send("GET", "/activity/7", false).statusCode());
            assertEquals(0, fixture.activitySourceReads, "Unauthorized sources are never resolved");
            var recordingsOnly = fixture.send("GET", "/recording/1", false);
            assertEquals(200, recordingsOnly.statusCode(), recordingsOnly.body());
            assertEquals(0, data(recordingsOnly).path("activity_rows").size());
            assertEquals(1, data(recordingsOnly).path("recordings").size());
            assertEquals(1, data(recordingsOnly).path("alerts").size());
            assertEquals(0, fixture.activityTargetReads);

            fixture.access.setCapabilityTier("radio", AccessTier.PUBLIC);
            fixture.access.setCapabilityTier("recordings", AccessTier.ADMIN);
            var activityOnly = fixture.send("GET", "/activity/7", true);
            assertEquals(200, activityOnly.statusCode(), activityOnly.body());
            assertEquals(1, data(activityOnly).path("activity_rows").size());
            assertEquals(0, data(activityOnly).path("recordings").size());
            assertEquals(401, fixture.send("GET", "/recording/1", false).statusCode());
            fixture.login();
            var administrator = fixture.send("GET", "/recording/1", false);
            assertEquals(200, administrator.statusCode(), administrator.body());
            assertEquals(1, data(administrator).path("activity_rows").size());
            assertEquals(1, data(administrator).path("recordings").size());
            assertFalse(administrator.body().contains(CallRelationsServiceTest.A));
            assertFalse(administrator.body().contains("source_call_uuid"));
            assertFalse(data(administrator).path("alerts").get(0).has("action"));
        }
    }

    @Test
    void boundsAndFailuresHaveSafeResponsesAndDoNotAcceptCallerSuppliedSourceReferences() throws Exception
    {
        try(var fixture = new Fixture(directory))
        {
            fixture.login();
            assertEquals(400, fixture.send("GET", "/activity/0", false).statusCode());
            assertEquals(400, fixture.send("GET", "/activity/99999999999999999999999999", false).statusCode());
            assertEquals(404, fixture.send("GET", "/other/7", false).statusCode());
            assertEquals(404, fixture.send("GET", "/activity/7/extra", false).statusCode());
            assertEquals(400, fixture.send("GET", "/activity/7?source_call_uuid=" + CallRelationsServiceTest.A, false).statusCode());
            var wrongMethod = fixture.send("POST", "/activity/7", false);
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("GET", wrongMethod.headers().firstValue("Allow").orElseThrow());
            assertEquals(404, fixture.send("GET", "/recording/999", false).statusCode());
            assertEquals(0, data(fixture.send("GET", "/activity/999", false)).path("recordings").size());
            fixture.failActivity = true;
            var unavailable = fixture.send("GET", "/activity/7", false);
            assertEquals(503, unavailable.statusCode());
            assertFalse(unavailable.body().contains("private-database-location"));
            assertFalse(unavailable.body().contains(CallRelationsServiceTest.A));
        }
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception
    { return JSON.readTree(response.body()).path("data"); }

    private static final class Fixture implements AutoCloseable
    {
        final WebAccessService access;
        final WebRequestSecurity security;
        final ManagedRecordingCatalog catalog;
        final HttpServer server;
        final URI origin;
        final HttpClient client = HttpClient.newHttpClient();
        String cookie = "";
        int activitySourceReads, activityTargetReads;
        boolean failActivity;

        Fixture(Path directory) throws Exception
        {
            Path database = directory.resolve("main.sqlite");
            SdrTrunkDatabaseStartup.createGlobalDatabase(database);
            access = new WebAccessService(database);
            access.provisionOrResetPrimaryAdmin("fixture-admin-password".toCharArray());
            access.setCapabilityTier("web-access", AccessTier.ADMIN);
            var authentication = new WebAuthenticationService(access);
            security = new WebRequestSecurity(access, authentication);
            Path recordingFile = directory.resolve("recordings.sqlite");
            catalog = new ManagedRecordingCatalog(recordingFile, directory);
            CallRelationsServiceTest.addRecording(recordingFile, 1, List.of(CallRelationsServiceTest.A));
            var service = new CallRelationsService(new CallRelationsService.ActivityLookup()
            {
                public String source(long id) throws SQLException
                {
                    activitySourceReads++;
                    if(failActivity) throw new SQLException("private-database-location " + CallRelationsServiceTest.A);
                    return id == 7 ? CallRelationsServiceTest.A : null;
                }
                public List<Map<String,Object>> find(List<String> sources)
                {
                    activityTargetReads++;
                    return List.of(Map.of("id", 7L, "channel_name", "Named channel"));
                }
            }, () -> catalog, sources -> List.of(Map.of("id", "alert-1", "title", "Fire call",
                "action", Map.of("kind", "live"))), rows -> rows);
            var controller = new CallRelationsHttpController(service, security);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            new WebSessionHttpController(access, authentication, security).register(server);
            server.createContext(CallRelationsHttpController.PATH, controller::handle);
            server.start();
            origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        void login() throws Exception
        {
            var request = HttpRequest.newBuilder(origin.resolve(WebSessionHttpController.LOGIN_PATH))
                .header("Origin", origin.toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                    Map.of("username", "admin", "password", "fixture-admin-password")))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        }

        HttpResponse<String> send(String method, String suffix, boolean notificationToken) throws Exception
        {
            var request = HttpRequest.newBuilder(origin.resolve(CallRelationsHttpController.PATH + suffix))
                .timeout(Duration.ofSeconds(5));
            if(!cookie.isEmpty()) request.header("Cookie", cookie);
            if(notificationToken) request.header("Authorization", "Bearer " + "a".repeat(43));
            return client.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        }

        public void close()
        { server.stop(0); security.close(); catalog.close(); }
    }
}
