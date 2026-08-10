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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiHttpResponseTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void sendsBoundedCollectionsInARecursiveSnakeCaseEnvelope() throws Exception
    {
        HttpServer server = server();
        server.createContext("/collection", exchange -> ApiHttpResponse.sendDataWithMeta(exchange, 206,
            List.of(new SiteRow(101, new ChannelState("Control", Map.of("lastSeenAt", 1234L)))), Map.of(
            "totalCount", 42,
            "hasMore", true,
            "nextBeforeId", 100)));
        server.start();

        try
        {
            HttpResponse<String> response = get(server, "/collection");
            JsonNode body = OBJECT_MAPPER.readTree(response.body());

            assertEquals(206, response.statusCode());
            assertEquals("application/json; charset=utf-8",
                response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
            assertTrue(body.get("data").isArray());
            assertEquals(101, body.at("/data/0/site_guid").longValue());
            assertEquals("Control", body.at("/data/0/channel_state/display_name").textValue());
            assertEquals(1234, body.at("/data/0/channel_state/observations/last_seen_at").longValue());
            assertEquals(42, body.at("/meta/total_count").intValue());
            assertTrue(body.at("/meta/has_more").booleanValue());
            assertEquals(100, body.at("/meta/next_before_id").longValue());
            assertFalse(body.has("rows"));
            assertFalse(body.get("meta").has("rows"));
        }
        finally
        {
            server.stop(0);
        }
    }

    @Test
    void sendsCompositeDataAndEventPayloadsUsingTheSameFieldConvention() throws Exception
    {
        HttpServer server = server();
        server.createContext("/composite", exchange -> ApiHttpResponse.sendData(exchange, 200, Map.of(
            "serverVersion", "1.0",
            "rows", List.of(Map.of("identityId", 7)),
            "nestedState", Map.of("activeChannelCount", 3))));
        server.start();

        try
        {
            JsonNode body = OBJECT_MAPPER.readTree(get(server, "/composite").body());
            assertEquals("1.0", body.at("/data/server_version").textValue());
            assertEquals(3, body.at("/data/nested_state/active_channel_count").intValue());
            assertEquals(7, body.at("/data/rows/0/identity_id").intValue());
            assertFalse(body.has("meta"));

            JsonNode event = OBJECT_MAPPER.readTree(ApiHttpResponse.encodePayload(Map.of(
                "eventType", "update",
                "eventData", List.of(Map.of("channelName", "Dispatch")))));
            assertEquals("update", event.get("event_type").textValue());
            assertEquals("Dispatch", event.at("/event_data/0/channel_name").textValue());
        }
        finally
        {
            server.stop(0);
        }
    }

    @Test
    void sendsStructuredErrorsWithStableCodeStatusAndOptionalField() throws Exception
    {
        HttpServer server = server();
        server.createContext("/field-error", exchange -> ApiHttpResponse.sendError(exchange, 400,
            "invalid_parameter", "limit must be between 1 and 500", "limit"));
        server.createContext("/generic-error", exchange -> ApiHttpResponse.sendError(exchange, 503,
            null, null));
        server.start();

        try
        {
            HttpResponse<String> fieldResponse = get(server, "/field-error");
            JsonNode fieldError = OBJECT_MAPPER.readTree(fieldResponse.body());
            assertEquals(400, fieldResponse.statusCode());
            assertEquals("invalid_parameter", fieldError.at("/error/code").textValue());
            assertEquals("limit must be between 1 and 500", fieldError.at("/error/message").textValue());
            assertEquals(400, fieldError.at("/error/status").intValue());
            assertEquals("limit", fieldError.at("/error/field").textValue());
            assertFalse(fieldError.has("data"));

            JsonNode genericError = OBJECT_MAPPER.readTree(get(server, "/generic-error").body());
            assertEquals("request_failed", genericError.at("/error/code").textValue());
            assertEquals("Request failed", genericError.at("/error/message").textValue());
            assertEquals(503, genericError.at("/error/status").intValue());
            assertFalse(genericError.at("/error").has("field"));
        }
        finally
        {
            server.stop(0);
        }
    }

    private static HttpServer server() throws Exception
    {
        return HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    }

    private static HttpResponse<String> get(HttpServer server, String path) throws Exception
    {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private record SiteRow(long siteGuid, ChannelState channelState)
    {
    }

    private record ChannelState(String displayName, Map<String,Long> observations)
    {
    }
}
