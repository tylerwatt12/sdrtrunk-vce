/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.web.settings.WebDisplaySettingsService;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebDisplaySettingsHttpControllerTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void readsUpdatesAndPersistsTheVersionedDisplayDocument() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        WebDisplaySettingsService service = new WebDisplaySettingsService(database);
        WebDisplaySettingsHttpController controller = new WebDisplaySettingsHttpController(service);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(WebDisplaySettingsHttpController.PATH, controller::handle);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            JsonNode initial = data(send(client, request(origin).GET()));
            assertEquals(1, initial.path("format_version").intValue());
            assertTrue(initial.path("show_encryption_details").booleanValue());

            HttpResponse<String> updatedResponse = send(client, jsonRequest(origin)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"show_encryption_details\":false}")));
            JsonNode updated = data(updatedResponse);
            assertFalse(updated.path("show_encryption_details").booleanValue());
            assertFalse(service.configuration().showEncryptionDetails());
            assertFalse(new WebDisplaySettingsService(database).configuration().showEncryptionDetails());

            try(Connection connection = SdrTrunkDatabase.open(database);
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT settings_json FROM application_settings WHERE key = ?"))
            {
                statement.setString(1, WebDisplaySettingsService.KEY);

                try(ResultSet resultSet = statement.executeQuery())
                {
                    assertTrue(resultSet.next());
                    JsonNode stored = OBJECT_MAPPER.readTree(resultSet.getString(1));
                    assertEquals(1, stored.path("format_version").intValue());
                    assertFalse(stored.path("show_encryption_details").booleanValue());
                }
            }

            assertEquals(400, send(client, jsonRequest(origin)
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
            assertEquals(400, send(client, jsonRequest(origin)
                .PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"show_encryption_details\":true,\"unexpected\":true}"))).statusCode());
            assertEquals(415, send(client, request(origin)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"show_encryption_details\":true}"))).statusCode());
            assertEquals(400, send(client, request(origin)
                .method("GET", HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
            HttpResponse<String> method = send(client, request(origin)
                .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(405, method.statusCode());
            assertEquals("GET, PUT", method.headers().firstValue("Allow").orElseThrow());
            assertEquals(404, send(client, HttpRequest.newBuilder(
                origin.resolve(WebDisplaySettingsHttpController.PATH + "?extra=1"))
                .timeout(Duration.ofSeconds(5)).GET()).statusCode());
            assertFalse(service.configuration().showEncryptionDetails());
        }
        finally
        {
            server.stop(0);
        }
    }

    private static HttpRequest.Builder jsonRequest(URI origin)
    {
        return request(origin).header("Content-Type", "application/json");
    }

    private static HttpRequest.Builder request(URI origin)
    {
        return HttpRequest.newBuilder(origin.resolve(WebDisplaySettingsHttpController.PATH))
            .timeout(Duration.ofSeconds(10));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception
    {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception
    {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return OBJECT_MAPPER.readTree(response.body()).path("data");
    }
}
