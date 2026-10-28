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
import io.github.dsheirer.preference.call.CallFilteringSettings;
import io.github.dsheirer.preference.call.CallManagementPreference;
import io.github.dsheirer.web.settings.CallFilteringSettingsService;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.prefs.BackingStoreException;
import org.junit.jupiter.api.Test;

class CallFilteringSettingsHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sharedPolicyUsesStrictInputAndRevisionCheckedWholePolicySaves() throws Exception
    {
        Calls calls = new Calls();
        CallFilteringSettingsHttpController controller =
            new CallFilteringSettingsHttpController(new CallFilteringSettingsService(calls));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(CallFilteringSettingsHttpController.PATH, controller::handle);
        server.start();
        try
        {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() +
                CallFilteringSettingsHttpController.PATH);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> initial = send(client, request(uri).GET());
            assertEquals(200, initial.statusCode());
            JsonNode data = MAPPER.readTree(initial.body());
            assertEquals(3, data.size());
            assertFalse(data.path("enabled").booleanValue());
            assertTrue(data.path("minimum_usable_voice_percent").isNull());
            String revision = initial.headers().firstValue("ETag").orElseThrow();
            assertEquals('"' + data.path("revision").textValue() + '"', revision);
            String enabled = "{\"enabled\":true,\"minimum_usable_voice_percent\":75}";
            HttpResponse<String> saved = put(client, uri, revision, enabled);
            assertEquals(200, saved.statusCode());
            String current = saved.headers().firstValue("ETag").orElseThrow();
            assertEquals(new CallFilteringSettings(true, 75), calls.settings);
            HttpResponse<String> conflict = put(client, uri, revision, enabled.replace("75", "90"));
            assertEquals(409, conflict.statusCode());
            assertEquals(current, conflict.headers().firstValue("ETag").orElseThrow());
            assertEquals(75, MAPPER.readTree(conflict.body()).path("minimum_usable_voice_percent").intValue());

            for(String invalid: new String[]{"{}", "null", enabled.replace("true", "\"true\""),
                enabled.replace("75", "null"), enabled.replace("75", "0"), enabled.replace("75", "101"),
                enabled.replace("75", "74.5"), enabled.replace("75", "\"75\""),
                enabled.replace("75", "2147483648")})
            {
                assertTrue(put(client, uri, current, invalid).statusCode() >= 400, invalid);
                assertEquals(new CallFilteringSettings(true, 75), calls.settings);
            }
            assertEquals(400, put(client, uri, current,
                enabled.replace("}", ",\"unexpected\":true}")).statusCode());
            assertEquals(400, put(client, uri, current,
                enabled.replace("}", ",\"enabled\":false}")).statusCode());
            assertEquals(428, send(client, request(uri).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(enabled))).statusCode());
            assertEquals(400, put(client, uri, "unquoted", enabled).statusCode());
            assertEquals(415, send(client, request(uri).header("If-Match", current)
                .PUT(HttpRequest.BodyPublishers.ofString(enabled))).statusCode());
            assertEquals(400, send(client, request(uri)
                .method("GET", HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
            assertEquals(400, send(client, request(URI.create(uri + "?extra=1")).GET()).statusCode());
            assertEquals(404, send(client, request(URI.create(uri + "/extra")).GET()).statusCode());
            assertEquals(405, send(client, request(uri).POST(HttpRequest.BodyPublishers.noBody())).statusCode());

            calls.failSave = true;
            assertEquals(500, put(client, uri, current, enabled.replace("75", "90")).statusCode());
            assertEquals(new CallFilteringSettings(true, 75), calls.settings);
            calls.failSave = false;
            HttpResponse<String> disabled = put(client, uri, current, enabled.replace("true", "false"));
            assertEquals(200, disabled.statusCode());
            assertEquals(new CallFilteringSettings(false, 75), calls.settings);
        }
        finally
        {
            server.stop(0);
        }
    }

    private static HttpRequest.Builder request(URI uri)
    {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5));
    }

    private static HttpResponse<String> put(HttpClient client, URI uri, String revision, String json) throws Exception
    {
        return send(client, request(uri).header("If-Match", revision).header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json)));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception
    {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final class Calls extends CallManagementPreference
    {
        private CallFilteringSettings settings = CallFilteringSettings.DISABLED;
        private boolean failSave;
        private Calls() { super(ignored -> {}); }
        @Override public CallFilteringSettings getCallFilteringSettings() { return settings; }
        @Override public synchronized void setCallFilteringSettings(CallFilteringSettings value) throws BackingStoreException
        {
            if(failSave) throw new BackingStoreException("Simulated save failure");
            settings = value;
        }
    }
}
