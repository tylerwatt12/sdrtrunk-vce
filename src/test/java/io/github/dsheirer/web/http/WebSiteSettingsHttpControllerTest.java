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
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.web.settings.WebSiteSettingsService;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.prefs.BackingStoreException;
import org.junit.jupiter.api.Test;

class WebSiteSettingsHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readsAndAtomicallyUpdatesOnlySharedReceiverSettings() throws Exception
    {
        TestNowPlayingPreference nowPlaying = new TestNowPlayingPreference();
        WebSiteSettingsService service = new WebSiteSettingsService(nowPlaying);
        WebSiteSettingsHttpController controller = new WebSiteSettingsHttpController(service);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(WebSiteSettingsHttpController.PATH, controller::handle);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> initialResponse = send(client, request(origin).GET());
            JsonNode initial = data(initialResponse);
            assertEquals("\"1\"", initialResponse.headers().firstValue("ETag").orElseThrow());
            assertEquals(1, initial.path("revision").longValue());
            assertFalse(initial.at("/settings/retain_idle_call_details").booleanValue());
            assertFalse(initial.at("/settings/clear_voice_decode_quality_on_call_end").booleanValue());
            assertEquals(1000, initial.at("/settings/traffic_grant_age_out_milliseconds").intValue());

            String update = """
                {
                  "retain_idle_call_details": true,
                  "clear_voice_decode_quality_on_call_end": true,
                  "traffic_grant_age_out_milliseconds": 2400
                }
                """;
            HttpResponse<String> updatedResponse = send(client, jsonRequest(origin).header("If-Match", "\"1\"")
                .PUT(HttpRequest.BodyPublishers.ofString(update)));
            JsonNode updated = data(updatedResponse);
            assertEquals("\"2\"", updatedResponse.headers().firstValue("ETag").orElseThrow());
            assertEquals(2, updated.path("revision").longValue());
            assertTrue(updated.at("/settings/retain_idle_call_details").booleanValue());
            assertTrue(updated.at("/settings/clear_voice_decode_quality_on_call_end").booleanValue());
            assertEquals(2400, updated.at("/settings/traffic_grant_age_out_milliseconds").intValue());
            assertEquals(1, nowPlaying.mSaveCount);

            HttpResponse<String> stale = send(client, jsonRequest(origin).header("If-Match", "\"1\"")
                .PUT(HttpRequest.BodyPublishers.ofString(update.replace("2400", "1200"))));
            assertEquals(409, stale.statusCode());
            assertEquals("\"2\"", stale.headers().firstValue("ETag").orElseThrow());
            JsonNode staleData = MAPPER.readTree(stale.body());
            assertEquals(2, staleData.path("revision").longValue());
            assertEquals(2400, staleData.at("/settings/traffic_grant_age_out_milliseconds").intValue());

            assertEquals(428, send(client, jsonRequest(origin)
                .PUT(HttpRequest.BodyPublishers.ofString(update))).statusCode());
            assertEquals(400, send(client, jsonRequest(origin).header("If-Match", "2")
                .PUT(HttpRequest.BodyPublishers.ofString(update))).statusCode());

            assertEquals(422, send(client, jsonRequest(origin).header("If-Match", "\"2\"")
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
            assertEquals(422, send(client, jsonRequest(origin).header("If-Match", "\"2\"")
                .PUT(HttpRequest.BodyPublishers.ofString(
                update.replace("2400", "99")))).statusCode());
            assertEquals(422, send(client, jsonRequest(origin).header("If-Match", "\"2\"")
                .PUT(HttpRequest.BodyPublishers.ofString(
                update.replace("2400", "200.5")))).statusCode());
            assertEquals(422, send(client, jsonRequest(origin).header("If-Match", "\"2\"")
                .PUT(HttpRequest.BodyPublishers.ofString(
                update.replace("}", ",\"unexpected\":true}")))).statusCode());
            assertEquals(415, send(client, request(origin).header("If-Match", "\"2\"")
                .PUT(HttpRequest.BodyPublishers.ofString(update))).statusCode());
            assertEquals(400, send(client, request(origin)
                .method("GET", HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
            assertEquals(400, send(client, HttpRequest.newBuilder(
                origin.resolve(WebSiteSettingsHttpController.PATH + "?extra=1"))
                .timeout(Duration.ofSeconds(5)).GET()).statusCode());

            nowPlaying.mFailNextSave = true;
            HttpResponse<String> failed = send(client, jsonRequest(origin).header("If-Match", "\"2\"")
                .PUT(HttpRequest.BodyPublishers.ofString(
                    update.replace("true", "false").replace("2400", "1200"))));
            assertEquals(500, failed.statusCode());
            assertEquals(2, service.snapshot().revision());
            assertEquals(2400, service.snapshot().settings().trafficGrantAgeOutMilliseconds());
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
        return HttpRequest.newBuilder(origin.resolve(WebSiteSettingsHttpController.PATH))
            .timeout(Duration.ofSeconds(10));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception
    {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception
    {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return MAPPER.readTree(response.body());
    }

    private static final class TestNowPlayingPreference extends NowPlayingPreference
    {
        private SiteSettingsSnapshot mSnapshot = new SiteSettingsSnapshot(1,
            new SiteSettings(false, false, DEFAULT_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS));
        private int mSaveCount;
        private boolean mFailNextSave;

        private TestNowPlayingPreference()
        {
            super(ignored -> {});
        }

        @Override
        public SiteSettingsSnapshot getSiteSettingsSnapshot()
        {
            return mSnapshot;
        }

        @Override
        public synchronized SiteSettingsUpdate replaceSiteSettings(long expectedRevision, SiteSettings settings)
            throws BackingStoreException
        {
            if(expectedRevision != mSnapshot.revision())
            {
                return new SiteSettingsUpdate(false, mSnapshot);
            }
            if(mFailNextSave)
            {
                mFailNextSave = false;
                throw new BackingStoreException("Simulated settings failure");
            }
            mSnapshot = new SiteSettingsSnapshot(mSnapshot.revision() + 1, settings);
            mSaveCount++;
            return new SiteSettingsUpdate(true, mSnapshot);
        }
    }
}
