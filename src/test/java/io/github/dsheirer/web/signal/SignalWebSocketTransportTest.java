/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.spectrum.stream.SpectrumFrame;
import io.github.dsheirer.spectrum.stream.SpectrumFrameCodec;
import io.github.dsheirer.spectrum.stream.SpectrumStreamService;
import io.github.dsheirer.spectrum.stream.SyntheticSpectrumFrameSource;
import io.github.dsheirer.web.WebApplicationService;
import io.github.dsheirer.web.WebResponses;
import io.github.dsheirer.web.access.AuthorizationSubject;
import io.github.dsheirer.web.access.FeatureAccessMode;
import io.github.dsheirer.web.access.InMemoryFeatureAccessPolicy;
import io.github.dsheirer.web.access.WebFeature;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.junit.jupiter.api.Test;

class SignalWebSocketTransportTest
{
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(8);

    @Test
    void publicAnonymousControlsAndReconnectReuseOneSyntheticSource() throws Exception
    {
        try(TestRig rig = TestRig.publicSignal())
        {
            TestListener firstListener = new TestListener();
            WebSocket first = rig.connect(firstListener, false, rig.origin()).get(TEST_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);
            first.sendText("{\"action\":\"subscribe\",\"maxFps\":30}", true).join();
            SpectrumFrame firstFrame = SpectrumFrameCodec.decode(firstListener.takeBinary());
            assertTrue((firstFrame.getFlags() & SpectrumFrame.FLAG_SYNTHETIC) != 0);
            assertEquals(128, firstFrame.getBinCount());
            assertEquals(1, rig.source.getStartCount());

            first.sendText("{\"action\":\"update\",\"maxFps\":5}", true).join();
            first.sendText("{\"action\":\"unsubscribe\"}", true).join();
            await(() -> rig.stream.getSubscriberCount() == 0);

            first.sendClose(WebSocket.NORMAL_CLOSURE, "test reconnect").join();
            firstListener.closed.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            TestListener secondListener = new TestListener();
            WebSocket second = rig.connect(secondListener, false, rig.origin()).get(TEST_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);
            second.sendText("{\"action\":\"subscribe\"}", true).join();
            SpectrumFrameCodec.decode(secondListener.takeBinary());

            assertEquals(1, rig.source.getStartCount(), "reconnect inside the grace period must not restart the source");
            assertEquals(1, rig.stream.getSourceStartCount());
            await(() -> rig.transport.getDeliveredFrameCount() >= 2);
        }
    }

    @Test
    void adminOnlyRejectsAnonymousAndAcceptsResolvedAdmin() throws Exception
    {
        try(TestRig rig = TestRig.adminOnlySignal())
        {
            ExecutionException denied = assertThrows(ExecutionException.class,
                () -> rig.connect(new TestListener(), false, rig.origin()).get(TEST_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS));
            assertTrue(denied.getCause() instanceof WebSocketHandshakeException);
            assertEquals(401, ((WebSocketHandshakeException)denied.getCause()).getResponse().statusCode());

            TestListener adminListener = new TestListener();
            WebSocket admin = rig.connect(adminListener, true, rig.origin()).get(TEST_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);
            admin.sendText("{\"action\":\"subscribe\"}", true).join();
            SpectrumFrameCodec.decode(adminListener.takeBinary());
            assertEquals(1, rig.transport.getActiveSessionCount());
            assertEquals(1, rig.transport.getRejectedHandshakeCount());
        }
    }

    @Test
    void sameOriginPolicyRejectsForeignOrigin() throws Exception
    {
        try(TestRig rig = TestRig.publicSignal())
        {
            ExecutionException denied = assertThrows(ExecutionException.class,
                () -> rig.connect(new TestListener(), false, "https://foreign.invalid").get(TEST_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS));
            assertTrue(denied.getCause() instanceof WebSocketHandshakeException);
            assertEquals(403, ((WebSocketHandshakeException)denied.getCause()).getResponse().statusCode());
            assertEquals(0, rig.transport.getActiveSessionCount());
            assertEquals(0, rig.source.getStartCount());
        }
    }

    @Test
    void explicitlyAllowedForeignOriginCanConnect() throws Exception
    {
        String trustedOrigin = "https://trusted.example";

        try(TestRig rig = TestRig.publicSignal(
            SignalOriginPolicy.sameOriginOr(List.of(URI.create(trustedOrigin)))))
        {
            TestListener listener = new TestListener();
            WebSocket socket = rig.connect(listener, false, trustedOrigin).get(TEST_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);
            socket.sendText("{\"action\":\"subscribe\"}", true).join();
            SpectrumFrameCodec.decode(listener.takeBinary());
            assertEquals(1, rig.transport.getActiveSessionCount());
        }
    }

    @Test
    void publicToAdminOnlyRevokesAnonymousLiveSession() throws Exception
    {
        try(TestRig rig = TestRig.publicSignal())
        {
            TestListener anonymousListener = new TestListener();
            WebSocket anonymous = rig.connect(anonymousListener, false, rig.origin()).get(TEST_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);
            TestListener adminListener = new TestListener();
            WebSocket admin = rig.connect(adminListener, true, rig.origin()).get(TEST_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);
            anonymous.sendText("{\"action\":\"subscribe\"}", true).join();
            admin.sendText("{\"action\":\"subscribe\"}", true).join();
            anonymousListener.takeBinary();
            adminListener.takeBinary();

            rig.policy.setMode(WebFeature.WIDEBAND_SIGNAL, FeatureAccessMode.ADMIN_ONLY);
            CloseEvent close = anonymousListener.closed.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(StatusCode.POLICY_VIOLATION, close.statusCode());
            SpectrumFrameCodec.decode(adminListener.takeBinary());
            await(() -> rig.transport.getActiveSessionCount() == 1 && rig.stream.getSubscriberCount() == 1);
            assertFalse(admin.isInputClosed());
            assertEquals(1, rig.transport.getRevokedSessionCount());
        }
    }

    @Test
    void malformedControlClosesWithoutStartingProducer() throws Exception
    {
        try(TestRig rig = TestRig.publicSignal())
        {
            TestListener listener = new TestListener();
            WebSocket socket = rig.connect(listener, false, rig.origin()).get(TEST_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);
            socket.sendText("{\"action\":\"update\",\"maxFps\":3.5}", true).join();
            CloseEvent close = listener.closed.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(StatusCode.POLICY_VIOLATION, close.statusCode());
            assertEquals(0, rig.source.getStartCount());
        }
    }

    @Test
    void handshakeSessionCountIsBounded() throws Exception
    {
        try(TestRig rig = TestRig.publicSignal(SignalOriginPolicy.sameOrigin(), 2))
        {
            rig.connect(new TestListener(), false, rig.origin()).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            rig.connect(new TestListener(), false, rig.origin()).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            ExecutionException denied = assertThrows(ExecutionException.class,
                () -> rig.connect(new TestListener(), false, rig.origin()).get(TEST_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS));
            assertTrue(denied.getCause() instanceof WebSocketHandshakeException);
            assertEquals(503, ((WebSocketHandshakeException)denied.getCause()).getResponse().statusCode());
            assertEquals(2, rig.transport.getActiveSessionCount());
        }
    }

    @Test
    void tenViewersShareOneProducerAndBoundedLatestOnlyFanout() throws Exception
    {
        try(TestRig rig = TestRig.publicSignal())
        {
            List<TestListener> listeners = new ArrayList<>();
            List<WebSocket> sockets = new ArrayList<>();

            for(int x = 0; x < 10; x++)
            {
                TestListener listener = new TestListener();
                listeners.add(listener);
                sockets.add(rig.connect(listener, false, rig.origin()).get(TEST_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS));
            }

            for(WebSocket socket: sockets)
            {
                socket.sendText("{\"action\":\"subscribe\"}", true).join();
            }

            for(TestListener listener: listeners)
            {
                SpectrumFrameCodec.decode(listener.takeBinary());
            }

            assertEquals(10, rig.transport.getActiveSessionCount());
            assertEquals(10, rig.stream.getSubscriberCount());
            assertEquals(1, rig.source.getStartCount());
            assertEquals(1, rig.stream.getSourceStartCount());
            long publishedBefore = rig.stream.getPublishedFrameCount();
            long deliveredBefore = rig.transport.getDeliveredFrameCount();
            await(() -> rig.transport.getDeliveredFrameCount() - deliveredBefore >
                rig.stream.getPublishedFrameCount() - publishedBefore);
        }
    }

    @Test
    void shutdownClosesSessionsAndTerminatesSendAndSourceExecutors() throws Exception
    {
        TestRig rig = TestRig.publicSignal();
        TestListener listener = new TestListener();
        WebSocket socket = rig.connect(listener, false, rig.origin()).get(TEST_TIMEOUT.toMillis(),
            TimeUnit.MILLISECONDS);
        socket.sendText("{\"action\":\"subscribe\"}", true).join();
        listener.takeBinary();

        rig.transport.close();
        CloseEvent close = listener.closed.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(StatusCode.SHUTDOWN, close.statusCode());
        rig.close();

        assertEquals(0, rig.transport.getActiveSessionCount());
        assertEquals(0, rig.stream.getSubscriberCount());
        assertTrue(rig.transport.isSendExecutorTerminated());
        assertTrue(rig.stream.isLifecycleExecutorTerminated());
        assertTrue(rig.source.isExecutorTerminated());
        assertFalse(rig.application.isRunning());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();

        while(!condition.getAsBoolean() && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }

        assertTrue(condition.getAsBoolean(), "condition did not become true before timeout");
    }

    private record CloseEvent(int statusCode, String reason)
    {
    }

    private static final class TestListener implements WebSocket.Listener
    {
        private final BlockingQueue<byte[]> binaryMessages = new LinkedBlockingQueue<>();
        private final CompletableFuture<CloseEvent> closed = new CompletableFuture<>();
        private final ByteArrayOutputStream partialBinary = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket webSocket)
        {
            webSocket.request(Long.MAX_VALUE);
        }

        @Override
        public synchronized CompletableFuture<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last)
        {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            partialBinary.writeBytes(bytes);

            if(last)
            {
                binaryMessages.add(partialBinary.toByteArray());
                partialBinary.reset();
            }

            return null;
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason)
        {
            closed.complete(new CloseEvent(statusCode, reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error)
        {
            closed.completeExceptionally(error);
        }

        private byte[] takeBinary() throws InterruptedException
        {
            byte[] frame = binaryMessages.poll(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(frame != null, "expected an SFFT binary frame before timeout");
            return frame;
        }
    }

    private static final class TestRig implements AutoCloseable
    {
        private static final String ADMIN_HEADER = "X-Sdrtrunk-Test-Admin";

        private final InMemoryFeatureAccessPolicy policy = InMemoryFeatureAccessPolicy.currentProfileDefaults();
        private final SyntheticSpectrumFrameSource source = new SyntheticSpectrumFrameSource(
            new SyntheticSpectrumFrameSource.Configuration(1, 851_012_500L, 2_400_000L, 128,
                Duration.ofMillis(25), "test synthetic spectrum"));
        private final SpectrumStreamService stream = new SpectrumStreamService(
            new SpectrumStreamService.Configuration(16, Duration.ofSeconds(2), "test spectrum grace"), source);
        private final SignalWebSocketTransport transport;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(TEST_TIMEOUT).build();
        private final WebApplicationService application;
        private final List<WebSocket> sockets = new ArrayList<>();
        private boolean closed;

        private TestRig(FeatureAccessMode accessMode, SignalOriginPolicy originPolicy, int maximumSessions)
        {
            policy.setMode(WebFeature.WIDEBAND_SIGNAL, accessMode);
            transport = new SignalWebSocketTransport(
                new SignalWebSocketTransport.Configuration(maximumSessions, 20, 30, Duration.ofMillis(100),
                    Duration.ofSeconds(2), Duration.ofSeconds(3), "test signal sender-"), stream, policy,
                request -> "true".equals(request.getHeaders().get(ADMIN_HEADER)) ?
                    AuthorizationSubject.AUTHENTICATED_ADMIN : AuthorizationSubject.ANONYMOUS, originPolicy);
            application = new WebApplicationService(WebApplicationService.Configuration.ephemeralLoopback(),
                new NotFoundHandler(), transport::configure);
            application.start();
        }

        private static TestRig publicSignal()
        {
            return publicSignal(SignalOriginPolicy.sameOrigin());
        }

        private static TestRig publicSignal(SignalOriginPolicy originPolicy)
        {
            return publicSignal(originPolicy, 16);
        }

        private static TestRig publicSignal(SignalOriginPolicy originPolicy, int maximumSessions)
        {
            return new TestRig(FeatureAccessMode.PUBLIC, originPolicy, maximumSessions);
        }

        private static TestRig adminOnlySignal()
        {
            return new TestRig(FeatureAccessMode.ADMIN_ONLY, SignalOriginPolicy.sameOrigin(), 16);
        }

        private String origin()
        {
            URI base = application.getBaseUri();
            return base.getScheme() + "://" + base.getAuthority();
        }

        private CompletableFuture<WebSocket> connect(TestListener listener, boolean admin, String origin)
        {
            URI base = application.getBaseUri();
            URI webSocketUri = URI.create("ws://" + base.getAuthority() + SignalWebSocketTransport.PATH);
            WebSocket.Builder builder = client.newWebSocketBuilder().connectTimeout(TEST_TIMEOUT)
                .header("Origin", origin);

            if(admin)
            {
                builder.header(ADMIN_HEADER, "true");
            }

            CompletableFuture<WebSocket> future = builder.buildAsync(webSocketUri, listener);
            future.thenAccept(sockets::add);
            return future;
        }

        @Override
        public void close()
        {
            if(closed)
            {
                return;
            }

            closed = true;

            for(WebSocket socket: List.copyOf(sockets))
            {
                socket.abort();
            }

            transport.close();
            application.close();
            stream.close();
            client.close();
        }
    }

    private static final class NotFoundHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback)
        {
            WebResponses.text(response, callback, 404, "text/plain; charset=utf-8", "not found");
            return true;
        }
    }
}
