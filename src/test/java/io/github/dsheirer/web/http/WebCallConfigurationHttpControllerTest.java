/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.stats.WebCallConfiguration;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WebCallConfigurationHttpControllerTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void readsAndAtomicallyUpdatesEveryBoundedCapacityField() throws Exception
    {
        AtomicReference<WebCallConfiguration> current = new AtomicReference<>(WebCallConfiguration.defaults());
        AtomicInteger updates = new AtomicInteger();
        WebCallConfigurationHttpController controller = new WebCallConfigurationHttpController(current::get,
            configuration -> {
                current.set(configuration);
                updates.incrementAndGet();
            }, () -> Map.of("active_listeners", 2));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(WebCallConfigurationHttpController.PATH, controller::handle);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> initialResponse = send(client, request(origin).GET());
            JsonNode initial = data(initialResponse);
            assertEquals(WebCallConfiguration.DEFAULT_MAXIMUM_LISTENERS,
                initial.at("/configuration/maximum_listeners").intValue());
            assertEquals(WebCallConfiguration.MINIMUM_SELECTED_SCAN_LISTS,
                initial.at("/limits/maximum_selected_scan_lists/minimum").intValue());
            assertEquals(WebCallConfiguration.MAXIMUM_SELECTED_SCAN_LISTS,
                initial.at("/limits/maximum_selected_scan_lists/maximum").intValue());
            assertEquals(2, initial.at("/status/active_listeners").intValue());

            WebCallConfiguration configured = new WebCallConfiguration(40, 8, 120, 1024, 256);
            Map<String,Object> payload = payload(configured);
            HttpResponse<String> updatedResponse = send(client, jsonRequest(origin)
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(payload))));
            assertEquals(200, updatedResponse.statusCode(), updatedResponse.body());
            assertEquals(configured, current.get());
            assertEquals(1, updates.get());
            assertEquals(120, data(updatedResponse).at("/configuration/maximum_browser_queue_calls").intValue());

            Map<String,Object> invalid = new LinkedHashMap<>(payload);
            invalid.put("maximum_listeners", 0);
            HttpResponse<String> invalidResponse = send(client, jsonRequest(origin)
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(invalid))));
            assertEquals(400, invalidResponse.statusCode());
            assertEquals("invalid_request", root(invalidResponse).at("/error/code").textValue());
            assertEquals(configured, current.get());
            assertEquals(1, updates.get());

            Map<String,Object> unknown = new LinkedHashMap<>(payload);
            unknown.put("unexpected", true);
            assertEquals(400, send(client, jsonRequest(origin)
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(unknown)))).statusCode());

            HttpResponse<String> missingContentType = send(client, request(origin)
                .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(payload))));
            assertEquals(415, missingContentType.statusCode());
            assertEquals("invalid_content_type", root(missingContentType).at("/error/code").textValue());

            HttpResponse<String> oversized = send(client, jsonRequest(origin)
                .PUT(HttpRequest.BodyPublishers.ofString("x".repeat(4097))));
            assertEquals(413, oversized.statusCode());
            assertEquals("request_too_large", root(oversized).at("/error/code").textValue());

            HttpResponse<String> malformed = send(client, jsonRequest(origin)
                .PUT(HttpRequest.BodyPublishers.ofString("{")));
            assertEquals(400, malformed.statusCode());
            assertEquals("invalid_request", root(malformed).at("/error/code").textValue());

            assertEquals(400, send(client, request(origin)
                .method("GET", HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
            HttpResponse<String> method = send(client, request(origin)
                .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(405, method.statusCode());
            assertEquals("GET, PUT", method.headers().firstValue("Allow").orElseThrow());
            assertEquals(404, send(client, HttpRequest.newBuilder(
                origin.resolve(WebCallConfigurationHttpController.PATH + "?extra=1"))
                .timeout(Duration.ofSeconds(5)).GET()).statusCode());
            assertEquals(configured, current.get());
            assertEquals(1, updates.get());
        }
        finally
        {
            server.stop(0);
        }
    }

    private static Map<String,Object> payload(WebCallConfiguration configuration)
    {
        return Map.of("maximum_listeners", configuration.maximumListeners(),
            "maximum_selected_scan_lists", configuration.maximumSelectedScanLists(),
            "maximum_browser_queue_calls", configuration.maximumBrowserQueueCalls(),
            "maximum_cached_calls", configuration.maximumCachedCalls(),
            "maximum_cached_audio_mib", configuration.maximumCachedAudioMiB());
    }

    private static HttpRequest.Builder jsonRequest(URI origin)
    {
        return request(origin).header("Content-Type", "application/json");
    }

    private static HttpRequest.Builder request(URI origin)
    {
        return HttpRequest.newBuilder(origin.resolve(WebCallConfigurationHttpController.PATH))
            .timeout(Duration.ofSeconds(10));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception
    {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception
    {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return root(response).get("data");
    }

    private static JsonNode root(HttpResponse<String> response) throws Exception
    {
        return OBJECT_MAPPER.readTree(response.body());
    }
}
