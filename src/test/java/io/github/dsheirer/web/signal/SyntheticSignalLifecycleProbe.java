/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web.signal;

import io.github.dsheirer.spectrum.stream.SpectrumFrameCodec;
import io.github.dsheirer.spectrum.stream.SpectrumStreamService;
import io.github.dsheirer.spectrum.stream.SyntheticSpectrumFrameSource;
import io.github.dsheirer.web.WebApplicationService;
import io.github.dsheirer.web.WebResponses;
import io.github.dsheirer.web.access.InMemoryFeatureAccessPolicy;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Manually invoked Java-25 lifecycle/RSS probe.  It is not a unit test and is intentionally absent from normal test
 * discovery.  Each printed phase leaves a five-second sampling window for an external process monitor.
 */
public final class SyntheticSignalLifecycleProbe
{
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private SyntheticSignalLifecycleProbe()
    {
    }

    public static void main(String[] args) throws Exception
    {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "origin");
        SyntheticSpectrumFrameSource source = new SyntheticSpectrumFrameSource(
            new SyntheticSpectrumFrameSource.Configuration(1, 851_000_000L, 10_000_000L, 4_096,
                Duration.ofMillis(50), "probe synthetic spectrum"));
        SpectrumStreamService stream = new SpectrumStreamService(
            new SpectrumStreamService.Configuration(16, Duration.ofSeconds(2), "probe spectrum lifecycle"), source);
        SignalWebSocketTransport transport = new SignalWebSocketTransport(
            SignalWebSocketTransport.Configuration.defaults(), stream,
            InMemoryFeatureAccessPolicy.currentProfileDefaults(), SignalSubjectResolver.anonymous(),
            SignalOriginPolicy.sameOrigin());
        WebApplicationService web = new WebApplicationService(
            WebApplicationService.Configuration.ephemeralLoopback(), new NotFoundHandler(), transport::configure);
        List<WebSocket> sockets = new ArrayList<>();

        try
        {
            web.start();
            forceGc();
            report("IDLE", transport, stream, source);
            Thread.sleep(5_000);

            URI uri = URI.create("ws://127.0.0.1:" + web.getLocalPort() + SignalWebSocketTransport.PATH);
            String origin = "http://127.0.0.1:" + web.getLocalPort();
            HttpClient client = HttpClient.newHttpClient();

            for(int x = 0; x < 10; x++)
            {
                ProbeListener listener = new ProbeListener();
                WebSocket socket = client.newWebSocketBuilder().header("Origin", origin)
                    .connectTimeout(TIMEOUT).buildAsync(uri, listener).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                sockets.add(socket);
                socket.sendText("{\"action\":\"subscribe\",\"maxFps\":20}", true).join();
                SpectrumFrameCodec.decode(listener.firstFrame.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            }

            forceGc();
            report("TEN_VIEWERS", transport, stream, source);
            Thread.sleep(5_000);

            closeSockets(sockets);
            sockets.clear();
            Thread.sleep(250);
            ProbeListener reconnectListener = new ProbeListener();
            WebSocket reconnect = client.newWebSocketBuilder().header("Origin", origin)
                .connectTimeout(TIMEOUT).buildAsync(uri, reconnectListener)
                .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            sockets.add(reconnect);
            reconnect.sendText("{\"action\":\"subscribe\"}", true).join();
            SpectrumFrameCodec.decode(reconnectListener.firstFrame.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            report("RECONNECTED", transport, stream, source);
            Thread.sleep(2_000);

            closeSockets(sockets);
            sockets.clear();
            Thread.sleep(2_500);
            forceGc();
            report("RETURNED_IDLE", transport, stream, source);
            Thread.sleep(5_000);
        }
        finally
        {
            closeSockets(sockets);
            transport.close();
            web.close();
            stream.close();
        }

        forceGc();
        report("CLOSED", transport, stream, source);
    }

    private static void closeSockets(List<WebSocket> sockets)
    {
        for(WebSocket socket: sockets)
        {
            try
            {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe phase complete").join();
            }
            catch(RuntimeException exception)
            {
                socket.abort();
            }
        }
    }

    private static void forceGc() throws InterruptedException
    {
        System.gc();
        Thread.sleep(250);
    }

    private static void report(String phase, SignalWebSocketTransport transport, SpectrumStreamService stream,
                               SyntheticSpectrumFrameSource source)
    {
        long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed();
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        System.out.printf("PROBE phase=%s pid=%d heapBytes=%d nonHeapBytes=%d threads=%d sessions=%d subscribers=%d " +
                "sourceRunning=%s sourceStarts=%d sourceStops=%d produced=%d delivered=%d%n",
            phase, ProcessHandle.current().pid(), heap, nonHeap, threads, transport.getActiveSessionCount(),
            stream.getSubscriberCount(), stream.isSourceRunning(), source.getStartCount(), source.getStopCount(),
            source.getProducedFrameCount(), transport.getDeliveredFrameCount());
        System.out.flush();
    }

    private static final class ProbeListener implements WebSocket.Listener
    {
        private final CompletableFuture<byte[]> firstFrame = new CompletableFuture<>();
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket webSocket)
        {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last)
        {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            pending.writeBytes(bytes);

            if(last && !firstFrame.isDone())
            {
                firstFrame.complete(pending.toByteArray());
            }

            webSocket.request(1);
            return null;
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
