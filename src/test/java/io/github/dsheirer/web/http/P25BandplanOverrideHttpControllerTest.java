/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P25BandplanOverrideHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void replacesAndValidatesTheCompleteP25OverrideDocument() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        P25BandplanOverrideRegistry registry =
            new P25BandplanOverrideRegistry(new ApplicationSettingsStore(database));
        P25BandplanOverrideHttpController controller = new P25BandplanOverrideHttpController(registry);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(P25BandplanOverrideHttpController.PATH, controller::handle);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            JsonNode initial = document(send(client, request(origin).GET()));
            assertTrue(initial.path("profiles").isArray());
            assertEquals(0, initial.path("profiles").size());

            String update = """
                {"profiles":[{
                  "wacn":781824,
                  "system":1183,
                  "rfss":null,
                  "site":null,
                  "bands":[{
                    "identifier":0,
                    "type":"FDMA",
                    "base_frequency":851006250,
                    "bandwidth":12500,
                    "channel_spacing":6250,
                    "transmit_offset":-45000000
                  }]
                }]}
                """;
            JsonNode saved = document(send(client, jsonRequest(origin)
                .PUT(HttpRequest.BodyPublishers.ofString(update))));
            assertEquals(1, saved.path("profiles").size());
            assertEquals(851006250L,
                saved.at("/profiles/0/bands/0/base_frequency").longValue());
            assertEquals(1, registry.getProfiles().size());

            assertEquals(422, send(client, jsonRequest(origin).PUT(HttpRequest.BodyPublishers.ofString(
                update.replace("\"rfss\":null", "\"rfss\":1")))).statusCode());
            assertEquals(422, send(client, jsonRequest(origin).PUT(HttpRequest.BodyPublishers.ofString(
                update.replace("\"profiles\"", "\"profiles\":[],\"unexpected\"")))).statusCode());
            assertEquals(415, send(client, request(origin)
                .PUT(HttpRequest.BodyPublishers.ofString(update))).statusCode());
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
        return HttpRequest.newBuilder(origin.resolve(P25BandplanOverrideHttpController.PATH))
            .timeout(Duration.ofSeconds(10));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception
    {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode document(HttpResponse<String> response) throws Exception
    {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return MAPPER.readTree(response.body());
    }
}
