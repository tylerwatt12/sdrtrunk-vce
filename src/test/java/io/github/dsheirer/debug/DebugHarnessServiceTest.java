/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.debug.DebugHarnessControlAdapter.HttpResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DebugHarnessServiceTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final HttpClient mClient = HttpClient.newHttpClient();
    private DebugHarnessService mService;

    @AfterEach
    void cleanup()
    {
        if(mService != null)
        {
            mService.close();
        }
    }

    @Test
    void disabledHarnessDoesNotBind() throws Exception
    {
        mService = service(false, false, new FakeControls());
        mService.start();
        assertFalse(mService.isRunning());
        assertEquals(-1, mService.getPort());
    }

    @Test
    void servesOnlyExactLoopbackReadEndpointsWithDefensiveHeaders() throws Exception
    {
        mService = service(true, false, new FakeControls());
        mService.start();
        assertTrue(mService.isRunning());
        assertTrue(mService.getPort() > 0);

        HttpResponse<String> status = get("/debug/v1/status");
        assertEquals(200, status.statusCode());
        assertEquals("no-store", status.headers().firstValue("cache-control").orElseThrow());
        assertEquals("nosniff", status.headers().firstValue("x-content-type-options").orElseThrow());
        assertTrue(status.headers().firstValue("access-control-allow-origin").isEmpty());
        JsonNode statusJson = OBJECT_MAPPER.readTree(status.body());
        assertEquals("127.0.0.1", statusJson.path("bind_address").asText());
        assertFalse(statusJson.path("controls_allowed").asBoolean());

        assertEquals(200, get("/debug/v1/snapshot").statusCode());
        assertEquals(200, get("/debug/v1/channels").statusCode());
        assertEquals(403, get("/debug/v1/threads").statusCode());
        HttpRequest threads = HttpRequest.newBuilder(uri("/debug/v1/threads")).GET()
            .header("X-SDRTrunk-Debug-Client", "1").build();
        assertEquals(200, mClient.send(threads, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, get("/debug/v1/missing").statusCode());
        assertEquals(404, get("/debug/v1/status/").statusCode());
        assertEquals(400, get("/debug/v1/status?extra=1").statusCode());

        HttpRequest wrongMethod = HttpRequest.newBuilder(uri("/debug/v1/status"))
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .header("Content-Type", "application/json").build();
        assertEquals(405, mClient.send(wrongMethod, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void mutationsRequireExplicitHeaderAndControlsFlag() throws Exception
    {
        FakeControls controls = new FakeControls();
        mService = service(true, true, controls);
        mService.start();

        HttpRequest browserForm = HttpRequest.newBuilder(uri("/debug/v1/session"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"duration_seconds\":60}"))
            .header("Content-Type", "application/json").build();
        assertEquals(403, mClient.send(browserForm, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, controls.mCreateCalls);

        HttpRequest create = HttpRequest.newBuilder(uri("/debug/v1/session"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"duration_seconds\":60}"))
            .header("Content-Type", "application/json")
            .header("X-SDRTrunk-Debug-Client", "1").build();
        HttpResponse<String> created = mClient.send(create, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());
        assertEquals(1, controls.mCreateCalls);

        HttpRequest start = HttpRequest.newBuilder(uri("/debug/v1/channels/start"))
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"configuration_id\":\"11111111-1111-4111-8111-111111111111\",\"revision\":1}"))
            .header("Content-Type", "application/json")
            .header("X-SDRTrunk-Debug-Client", "1")
            .header("X-SDRTrunk-Debug-Session", "token").build();
        assertEquals(200, mClient.send(start, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(1, controls.mSetCalls);
        assertTrue(controls.mLastProcessing);
    }

    @Test
    void malformedAndOversizedMutationBodiesAreBounded() throws Exception
    {
        mService = service(true, true, new FakeControls());
        mService.start();

        HttpRequest malformed = HttpRequest.newBuilder(uri("/debug/v1/session"))
            .POST(HttpRequest.BodyPublishers.ofString("not-json"))
            .header("Content-Type", "application/json")
            .header("X-SDRTrunk-Debug-Client", "1").build();
        assertEquals(400, mClient.send(malformed, HttpResponse.BodyHandlers.ofString()).statusCode());

        String oversized = "{\"padding\":\"" + "x".repeat(9_000) + "\"}";
        HttpRequest request = HttpRequest.newBuilder(uri("/debug/v1/session"))
            .POST(HttpRequest.BodyPublishers.ofString(oversized))
            .header("Content-Type", "application/json")
            .header("X-SDRTrunk-Debug-Client", "1").build();
        assertEquals(400, mClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private DebugHarnessService service(boolean enabled, boolean controlsAllowed, DebugHarnessControlAdapter controls)
    {
        DebugHarnessConfiguration configuration = new DebugHarnessConfiguration(enabled, controlsAllowed, 8091);
        DebugHarnessTelemetry telemetry = new DebugHarnessTelemetry(List::of, null, System::currentTimeMillis,
            System::nanoTime, null, () -> null);
        return new DebugHarnessService(configuration, telemetry, controls, 0);
    }

    private HttpResponse<String> get(String path) throws Exception
    {
        return mClient.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path)
    {
        return URI.create("http://127.0.0.1:" + mService.getPort() + path);
    }

    private static final class FakeControls implements DebugHarnessControlAdapter
    {
        private int mCreateCalls;
        private int mSetCalls;
        private boolean mLastProcessing;

        @Override
        public byte[] channelsJson()
        {
            return "{\"channels\":[]}".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public HttpResult createSession(long durationSeconds)
        {
            mCreateCalls++;
            return result(201, "{\"session_id\":\"token\",\"revision\":1}");
        }

        @Override
        public HttpResult getSession(String token)
        {
            return result(200, "{\"session_id\":\"token\",\"revision\":1}");
        }

        @Override
        public HttpResult endSession(String token)
        {
            return result(200, "{\"state\":\"ended\"}");
        }

        @Override
        public HttpResult setChannel(String token, long revision, String configurationId, boolean processing)
        {
            mSetCalls++;
            mLastProcessing = processing;
            return result(200, "{\"changed\":true}");
        }

        private static HttpResult result(int status, String body)
        {
            return new HttpResult(status, body.getBytes(StandardCharsets.UTF_8));
        }
    }
}
