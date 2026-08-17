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
import io.github.dsheirer.preference.location.ReceiverLocation;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReceiverLocationHttpControllerTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void readsUpdatesValidatesAndClearsReceiverCoordinates() throws Exception
    {
        AtomicReference<Optional<ReceiverLocation>> current = new AtomicReference<>(Optional.empty());
        AtomicInteger updates = new AtomicInteger();
        ReceiverLocationHttpController controller = new ReceiverLocationHttpController(current::get, location -> {
            current.set(location);
            updates.incrementAndGet();
        });
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(ReceiverLocationHttpController.PATH, controller::handle);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            JsonNode initial = data(send(client, request(origin).GET()));
            assertTrue(!initial.get("configured").booleanValue());
            assertTrue(initial.get("latitude").isNull());
            assertTrue(initial.get("longitude").isNull());

            HttpResponse<String> updated = send(client, jsonRequest(origin).PUT(
                HttpRequest.BodyPublishers.ofString("{\"latitude\":41.50481,\"longitude\":-81.69312}")));
            assertEquals(200, updated.statusCode(), updated.body());
            assertEquals(new ReceiverLocation(41.50481d, -81.69312d), current.get().orElseThrow());
            assertEquals(1, updates.get());
            assertTrue(data(updated).get("configured").booleanValue());

            assertEquals(400, send(client, jsonRequest(origin).PUT(
                HttpRequest.BodyPublishers.ofString("{\"latitude\":91,\"longitude\":0}"))).statusCode());
            assertEquals(400, send(client, jsonRequest(origin).PUT(
                HttpRequest.BodyPublishers.ofString("{\"latitude\":41}"))).statusCode());
            assertEquals(400, send(client, jsonRequest(origin).PUT(
                HttpRequest.BodyPublishers.ofString(
                    "{\"latitude\":41,\"longitude\":-81,\"unexpected\":true}"))).statusCode());
            assertEquals(415, send(client, request(origin).PUT(
                HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
            assertEquals(1, updates.get());

            HttpResponse<String> cleared = send(client, request(origin).DELETE());
            assertEquals(200, cleared.statusCode(), cleared.body());
            assertTrue(current.get().isEmpty());
            assertEquals(2, updates.get());
            assertTrue(!data(cleared).get("configured").booleanValue());

            assertEquals(400, send(client, request(origin).method("DELETE",
                HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
            HttpResponse<String> method = send(client, request(origin)
                .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(405, method.statusCode());
            assertEquals("GET, PUT, DELETE", method.headers().firstValue("Allow").orElseThrow());
            assertEquals(404, send(client, HttpRequest.newBuilder(
                origin.resolve(ReceiverLocationHttpController.PATH + "?extra=1"))
                .timeout(Duration.ofSeconds(5)).GET()).statusCode());
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
        return HttpRequest.newBuilder(origin.resolve(ReceiverLocationHttpController.PATH))
            .timeout(Duration.ofSeconds(10));
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
}
