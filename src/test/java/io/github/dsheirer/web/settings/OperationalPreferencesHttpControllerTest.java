package io.github.dsheirer.web.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.web.http.OperationalPreferencesHttpController;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OperationalPreferencesHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void strictOneFieldEndpointPublishesOnlyOperationalSettingsAndRejectsStaleWrites() throws Exception
    {
        OperationalPreferencesServiceTest.Fixture fixture = new OperationalPreferencesServiceTest.Fixture();
        OperationalPreferencesHttpController controller = new OperationalPreferencesHttpController(fixture.service);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(OperationalPreferencesHttpController.PATH, controller::handle);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> initialResponse = send(client, request(origin, "").GET());
            JsonNode initial = MAPPER.readTree(initialResponse.body());
            assertEquals(200, initialResponse.statusCode());
            assertEquals(8, initial.path("settings").size());
            assertTrue(initial.at("/settings/stats_logging_enabled").isBoolean());
            assertEquals("MP3", initial.at("/settings/audio_record_format").textValue());
            assertFalse(initial.toString().contains("directory"));
            assertFalse(initial.toString().contains("certificate"));
            assertFalse(initial.toString().contains("vault"));
            String etag = initialResponse.headers().firstValue("ETag").orElseThrow();
            assertEquals('"' + initial.path("revision").textValue() + '"', etag);

            HttpResponse<String> updatedResponse = send(client,
                jsonRequest(origin, "/stats_logging_enabled").header("If-Match", etag)
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":true}")));
            assertEquals(200, updatedResponse.statusCode());
            assertTrue(MAPPER.readTree(updatedResponse.body()).at("/settings/stats_logging_enabled").booleanValue());
            assertEquals(1, fixture.application.mWrites);

            HttpResponse<String> stale = send(client,
                jsonRequest(origin, "/stats_logging_retention_days").header("If-Match", etag)
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":60}")));
            assertEquals(409, stale.statusCode());
            assertEquals(30, fixture.application.getStatsLoggingRetentionDays());
            String newEtag = updatedResponse.headers().firstValue("ETag").orElseThrow();

            assertEquals(428, send(client, jsonRequest(origin, "/stats_logging_retention_days")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":60}"))).statusCode());
            assertEquals(400, send(client, jsonRequest(origin, "/stats_logging_retention_days")
                .header("If-Match", "invalid").PUT(HttpRequest.BodyPublishers.ofString("{\"value\":60}")))
                .statusCode());
            assertEquals(422, send(client, jsonRequest(origin, "/stats_logging_retention_days")
                .header("If-Match", newEtag).PUT(HttpRequest.BodyPublishers.ofString("{\"value\":366}")))
                .statusCode());
            assertEquals(422, send(client, jsonRequest(origin, "/stats_logging_retention_days")
                .header("If-Match", newEtag).PUT(HttpRequest.BodyPublishers.ofString("{\"value\":30.5}")))
                .statusCode());
            assertEquals(400, send(client, jsonRequest(origin, "/stats_logging_retention_days")
                .header("If-Match", newEtag).PUT(HttpRequest.BodyPublishers.ofString("{\"value\":60,\"other\":1}")))
                .statusCode());
            assertEquals(404, send(client, jsonRequest(origin, "/unknown")
                .header("If-Match", newEtag).PUT(HttpRequest.BodyPublishers.ofString("{\"value\":true}")))
                .statusCode());
            assertEquals(405, send(client, request(origin, "/stats_logging_enabled").GET()).statusCode());
            assertEquals(400, send(client, request(origin, "?extra=1").GET()).statusCode());
        }
        finally
        {
            server.stop(0);
        }
    }

    private static HttpRequest.Builder jsonRequest(URI origin, String suffix)
    {
        return request(origin, suffix).header("Content-Type", "application/json");
    }

    private static HttpRequest.Builder request(URI origin, String suffix)
    {
        return HttpRequest.newBuilder(origin.resolve(OperationalPreferencesHttpController.PATH + suffix))
            .timeout(Duration.ofSeconds(10));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception
    {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
