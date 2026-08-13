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
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
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

    @Test
    void concurrentThreadRequestsShareOneBoundedSnapshotForThirtySeconds() throws Exception
    {
        AtomicLong nanoClock = new AtomicLong(1L);
        AtomicInteger captures = new AtomicInteger();
        Supplier<byte[]> capture = () -> ("{\"capture\":" + captures.incrementAndGet() + "}")
            .getBytes(StandardCharsets.UTF_8);
        mService = service(true, false, new FakeControls(), 0, nanoClock::get, capture);
        mService.start();

        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();

        for(int x = 0; x < 8; x++)
        {
            requests.add(mClient.sendAsync(threadRequest(), HttpResponse.BodyHandlers.ofString()));
        }

        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        assertEquals(1, captures.get());

        for(CompletableFuture<HttpResponse<String>> request: requests)
        {
            assertEquals(200, request.get().statusCode());
            assertEquals("{\"capture\":1}", request.get().body());
        }

        nanoClock.addAndGet(DebugHarnessService.THREAD_SNAPSHOT_COOLDOWN_NANOSECONDS - 1L);
        assertEquals("{\"capture\":1}", mClient.send(threadRequest(), HttpResponse.BodyHandlers.ofString()).body());
        assertEquals(1, captures.get());

        nanoClock.incrementAndGet();
        assertEquals("{\"capture\":2}", mClient.send(threadRequest(), HttpResponse.BodyHandlers.ofString()).body());
        assertEquals(2, captures.get());

        mService.close();
        mService = service(true, false, new FakeControls(), 0, System::nanoTime,
            () -> new byte[DebugHarnessService.MAXIMUM_THREAD_SNAPSHOT_BYTES + 1_024]);
        mService.start();
        HttpResponse<byte[]> bounded = mClient.send(threadRequest(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, bounded.statusCode());
        assertTrue(bounded.body().length <= DebugHarnessService.MAXIMUM_THREAD_SNAPSHOT_BYTES);
        assertTrue(OBJECT_MAPPER.readTree(bounded.body()).path("truncated").asBoolean());
    }

    @Test
    void saturatedSlowRequestsStayOnBoundedWorkersAndCannotCollectTelemetry() throws Exception
    {
        AtomicInteger telemetryCollections = new AtomicInteger();
        CountDownLatch samplerEntered = new CountDownLatch(1);
        CountDownLatch releaseSampler = new CountDownLatch(1);
        DebugHarnessTelemetry telemetry = new DebugHarnessTelemetry(() -> {
            telemetryCollections.incrementAndGet();
            samplerEntered.countDown();
            await(releaseSampler);
            return List.of();
        }, null, System::currentTimeMillis, System::nanoTime, null, () -> null);
        BlockingControls controls = new BlockingControls();
        DebugHarnessConfiguration configuration = new DebugHarnessConfiguration(true, false, 8091);
        mService = new DebugHarnessService(configuration, telemetry, controls, 0);
        mService.start();

        try
        {
            assertTrue(samplerEntered.await(2, TimeUnit.SECONDS));

            for(int x = 0; x < 12; x++)
            {
                assertEquals(200, get("/debug/v1/snapshot").statusCode());
            }

            assertEquals(1, telemetryCollections.get());
            List<CompletableFuture<HttpResponse<String>>> accepted = new ArrayList<>();

            for(int x = 0; x < 10; x++)
            {
                accepted.add(mClient.sendAsync(HttpRequest.newBuilder(uri("/debug/v1/channels")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
            }

            assertTrue(controls.mTwoWorkersEntered.await(2, TimeUnit.SECONDS));
            assertTrue(awaitCondition(() -> mService.getActiveHttpWorkerCount() == 2 &&
                mService.getQueuedHttpRequestCount() == 8, Duration.ofSeconds(3)));

            //One more request exercises the rejection path.  A CallerRuns policy would enter the handler on the
            //HttpServer dispatcher, increasing this count and exposing a non-debug-worker thread name.
            CompletableFuture<HttpResponse<String>> rejected = mClient.sendAsync(
                HttpRequest.newBuilder(uri("/debug/v1/channels")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertFalse(controls.mThirdRequestEntered.await(250, TimeUnit.MILLISECONDS));
            assertEquals(2, controls.mCalls.get());
            assertEquals(1, telemetryCollections.get());
            assertTrue(controls.mThreadNames.stream().allMatch(name -> name.startsWith("receiver debug http ")));

            controls.mRelease.countDown();
            CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            assertTrue(accepted.stream().allMatch(request -> request.join().statusCode() == 200));
            rejected.cancel(true);
            HttpRequest statusAfterSaturation = HttpRequest.newBuilder(uri("/debug/v1/status"))
                .timeout(Duration.ofSeconds(2)).GET().build();
            assertEquals(200, mClient.send(statusAfterSaturation, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        finally
        {
            controls.mRelease.countDown();
            releaseSampler.countDown();
        }
    }

    @Test
    void disconnectedClientDoesNotPreventClosingAndRebinding() throws Exception
    {
        mService = service(true, false, new FakeControls());
        mService.start();
        int port = mService.getPort();

        try(Socket socket = new Socket("127.0.0.1", port))
        {
            OutputStream output = socket.getOutputStream();
            output.write(("GET /debug/v1/status HTTP/1.1\r\nHost: 127.0.0.1:" + port +
                "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            //Disconnect without consuming the response.
        }

        mService.close();
        mService = service(true, false, new FakeControls(), port, System::nanoTime,
            () -> "{}".getBytes(StandardCharsets.UTF_8));
        mService.start();
        assertEquals(port, mService.getPort());
        assertEquals(200, get("/debug/v1/status").statusCode());
    }

    @Test
    void closeStopsListenerClosesControlsThenWaitsForDiagnosticWorkers() throws Exception
    {
        CloseTrackingControls controls = new CloseTrackingControls();
        CountDownLatch samplerEntered = new CountDownLatch(1);
        CountDownLatch samplerInterrupted = new CountDownLatch(1);
        CountDownLatch releaseSampler = new CountDownLatch(1);
        AtomicBoolean controlsClosedWhenSamplerInterrupted = new AtomicBoolean();
        DebugHarnessTelemetry telemetry = new DebugHarnessTelemetry(() -> {
            samplerEntered.countDown();
            boolean released = false;

            while(!released)
            {
                try
                {
                    releaseSampler.await();
                    released = true;
                }
                catch(InterruptedException e)
                {
                    controlsClosedWhenSamplerInterrupted.set(controls.mClosed.get());
                    samplerInterrupted.countDown();
                    //Remain blocked until the test releases us, proving close waits after issuing cancellation.
                }
            }

            return List.of();
        }, null, System::currentTimeMillis, System::nanoTime, null, () -> null);
        DebugHarnessConfiguration configuration = new DebugHarnessConfiguration(true, false, 8091);
        mService = new DebugHarnessService(configuration, telemetry, controls, 0);
        mService.start();
        assertTrue(samplerEntered.await(2, TimeUnit.SECONDS));
        CompletableFuture<HttpResponse<String>> request = mClient.sendAsync(
            HttpRequest.newBuilder(uri("/debug/v1/channels")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertTrue(controls.mHttpEntered.await(2, TimeUnit.SECONDS));

        CompletableFuture<Void> closing = CompletableFuture.runAsync(mService::close);

        try
        {
            assertTrue(controls.mCloseCalled.await(2, TimeUnit.SECONDS));
            assertFalse(mService.isRunning());
            assertTrue(samplerInterrupted.await(2, TimeUnit.SECONDS));
            assertTrue(controls.mHttpInterrupted.await(2, TimeUnit.SECONDS));
            assertTrue(controlsClosedWhenSamplerInterrupted.get());
            assertTrue(controls.mControlsClosedWhenHttpInterrupted.get());
            assertFalse(closing.isDone());
        }
        finally
        {
            releaseSampler.countDown();
            controls.mReleaseHttp.countDown();
        }

        closing.get(DebugHarnessService.SHUTDOWN_TIMEOUT_MILLISECONDS + 1_000L, TimeUnit.MILLISECONDS);
        assertTrue(mService.areDiagnosticWorkersTerminated());
        request.cancel(true);
    }

    private DebugHarnessService service(boolean enabled, boolean controlsAllowed, DebugHarnessControlAdapter controls)
    {
        DebugHarnessConfiguration configuration = new DebugHarnessConfiguration(enabled, controlsAllowed, 8091);
        DebugHarnessTelemetry telemetry = new DebugHarnessTelemetry(List::of, null, System::currentTimeMillis,
            System::nanoTime, null, () -> null);
        return new DebugHarnessService(configuration, telemetry, controls, 0);
    }

    private DebugHarnessService service(boolean enabled, boolean controlsAllowed, DebugHarnessControlAdapter controls,
                                        int port, java.util.function.LongSupplier nanoClock,
                                        Supplier<byte[]> threadSnapshotSupplier)
    {
        DebugHarnessConfiguration configuration = new DebugHarnessConfiguration(enabled, controlsAllowed, 8091);
        DebugHarnessTelemetry telemetry = new DebugHarnessTelemetry(List::of, null, System::currentTimeMillis,
            System::nanoTime, null, () -> null);
        return new DebugHarnessService(configuration, telemetry, controls, port, nanoClock, threadSnapshotSupplier);
    }

    private HttpRequest threadRequest()
    {
        return HttpRequest.newBuilder(uri("/debug/v1/threads")).GET()
            .header("X-SDRTrunk-Debug-Client", "1").build();
    }

    private static boolean awaitCondition(BooleanSupplier condition, Duration timeout) throws InterruptedException
    {
        long deadline = System.nanoTime() + timeout.toNanos();

        while(System.nanoTime() < deadline)
        {
            if(condition.getAsBoolean())
            {
                return true;
            }

            TimeUnit.MILLISECONDS.sleep(10L);
        }

        return condition.getAsBoolean();
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            latch.await();
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
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

    private static final class BlockingControls implements DebugHarnessControlAdapter
    {
        private final AtomicInteger mCalls = new AtomicInteger();
        private final CountDownLatch mTwoWorkersEntered = new CountDownLatch(2);
        private final CountDownLatch mThirdRequestEntered = new CountDownLatch(3);
        private final CountDownLatch mRelease = new CountDownLatch(1);
        private final Set<String> mThreadNames = ConcurrentHashMap.newKeySet();

        @Override
        public byte[] channelsJson()
        {
            mCalls.incrementAndGet();
            mThreadNames.add(Thread.currentThread().getName());
            mTwoWorkersEntered.countDown();
            mThirdRequestEntered.countDown();
            await(mRelease);
            return "{\"channels\":[]}".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public HttpResult createSession(long durationSeconds)
        {
            return result(403, "{}");
        }

        @Override
        public HttpResult getSession(String token)
        {
            return result(403, "{}");
        }

        @Override
        public HttpResult endSession(String token)
        {
            return result(403, "{}");
        }

        @Override
        public HttpResult setChannel(String token, long revision, String configurationId, boolean processing)
        {
            return result(403, "{}");
        }

        private static HttpResult result(int status, String body)
        {
            return new HttpResult(status, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class CloseTrackingControls implements DebugHarnessControlAdapter, AutoCloseable
    {
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private final AtomicBoolean mControlsClosedWhenHttpInterrupted = new AtomicBoolean();
        private final CountDownLatch mCloseCalled = new CountDownLatch(1);
        private final CountDownLatch mHttpEntered = new CountDownLatch(1);
        private final CountDownLatch mHttpInterrupted = new CountDownLatch(1);
        private final CountDownLatch mReleaseHttp = new CountDownLatch(1);

        @Override
        public byte[] channelsJson()
        {
            mHttpEntered.countDown();

            try
            {
                mReleaseHttp.await();
            }
            catch(InterruptedException e)
            {
                mControlsClosedWhenHttpInterrupted.set(mClosed.get());
                mHttpInterrupted.countDown();
                Thread.currentThread().interrupt();
            }

            return "{\"channels\":[]}".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public HttpResult createSession(long durationSeconds)
        {
            return result(403, "{}");
        }

        @Override
        public HttpResult getSession(String token)
        {
            return result(403, "{}");
        }

        @Override
        public HttpResult endSession(String token)
        {
            return result(403, "{}");
        }

        @Override
        public HttpResult setChannel(String token, long revision, String configurationId, boolean processing)
        {
            return result(403, "{}");
        }

        @Override
        public void close()
        {
            mClosed.set(true);
            mCloseCalled.countDown();
        }

        private static HttpResult result(int status, String body)
        {
            return new HttpResult(status, body.getBytes(StandardCharsets.UTF_8));
        }
    }
}
