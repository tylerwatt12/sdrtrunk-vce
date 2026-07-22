/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.application.service.LiveContext;
import io.github.dsheirer.application.service.LiveContextResolver;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionDescriptor;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionScope;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.dsp.symbol.stream.SelectedChannelSymbolSource;
import io.github.dsheirer.dsp.symbol.stream.SymbolFrame;
import io.github.dsheirer.dsp.symbol.stream.SymbolFrameCodec;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.spectrum.stream.SpectrumFrame;
import io.github.dsheirer.spectrum.stream.SpectrumFrameCodec;
import io.github.dsheirer.web.WebApplicationService;
import io.github.dsheirer.web.WebResponses;
import io.github.dsheirer.web.access.AuthorizationSubject;
import io.github.dsheirer.web.access.InMemoryFeatureAccessPolicy;
import io.github.dsheirer.web.access.RemoteAddressAdmissionPolicy;
import io.github.dsheirer.web.signal.SignalOriginPolicy;
import io.github.dsheirer.web.signal.SignalSubjectResolver;
import io.github.dsheirer.web.signal.SignalSubjectResolver.SignalAuthorization;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.junit.jupiter.api.Test;

class SelectedChannelDiagnosticWebSocketTransportTest
{
    private static final String SELECTION_ID = "selected-channel-test";
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(8);

    @Test
    void adminSocketSwitchesSourcesInPlaceAndRejectsCompetingOrMalformedClients() throws Exception
    {
        try(TestRig rig = new TestRig())
        {
            ExecutionException denied = assertThrows(ExecutionException.class,
                () -> rig.connect(new TestListener(), false).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(401, ((WebSocketHandshakeException)denied.getCause()).getResponse().statusCode());

            TestListener listener = new TestListener();
            WebSocket socket = rig.connect(listener, true).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            String ready = listener.takeTextContaining("\"state\":\"ready\"");
            assertTrue(ready.contains("\"signalFps\":12"));
            assertTrue(ready.contains("\"symbolBatchSize\":120"));

            ExecutionException busy = assertThrows(ExecutionException.class,
                () -> rig.connect(new TestListener(), true).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(409, ((WebSocketHandshakeException)busy.getCause()).getResponse().statusCode());

            socket.sendText("{\"action\":\"subscribe\",\"requestId\":1," +
                "\"selectionId\":\"" + SELECTION_ID + "\",\"view\":\"symbols\"}", true).join();
            assertTrue(listener.takeTextContaining("\"state\":\"live\"")
                .contains("\"view\":\"symbols\""));
            await(() -> rig.decoder.getSymbolObserverCount() == 1);
            broadcastBatch(rig.decoder, 0.75f);

            SymbolFrame symbols = SymbolFrameCodec.decode(listener.takeBinary());
            assertEquals(0, symbols.getGeneration());
            assertEquals(SelectedChannelSymbolSource.BATCH_SIZE, symbols.getSymbolCount());
            assertEquals(0.75f, symbols.getSymbol(0));

            socket.sendText("{\"action\":\"update\",\"requestId\":2,\"view\":\"signal\"}", true).join();
            assertTrue(listener.takeTextContaining("\"state\":\"live\"")
                .contains("\"view\":\"signal\""));
            assertEquals(0, rig.decoder.getSymbolObserverCount());

            for(int x = 0; x < 8; x++)
            {
                rig.sampleSource.emit(tone(4_096, x));
                Thread.sleep(15);
            }

            SpectrumFrame spectrum = SpectrumFrameCodec.decode(listener.takeBinary());
            assertEquals(1, spectrum.getTargetGeneration());
            assertEquals(4_096, spectrum.getBinCount());
            assertEquals(48_000L, spectrum.getSampleRateHz());

            socket.sendText("{\"action\":\"update\",\"requestId\":3,\"view\":\"symbols\"}", true).join();
            assertTrue(listener.takeTextContaining("\"state\":\"live\"")
                .contains("\"generation\":2"));
            await(() -> rig.decoder.getSymbolObserverCount() == 1);

            socket.sendText("{\"action\":\"update\",\"requestId\":3,\"view\":\"signal\"}", true).join();
            CloseEvent close = listener.closed.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(StatusCode.POLICY_VIOLATION, close.statusCode());
            await(() -> !rig.workspaceLease.isActive() && rig.decoder.getSymbolObserverCount() == 0 &&
                rig.service.getActiveSessionCount() == 0);
        }
    }

    @Test
    void exactSelectionEndSendsStateThenClosesAndReleasesWorkspace() throws Exception
    {
        try(TestRig rig = new TestRig())
        {
            TestListener listener = new TestListener();
            WebSocket socket = rig.connect(listener, true).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            listener.takeTextContaining("\"state\":\"ready\"");
            socket.sendText("{\"action\":\"subscribe\",\"requestId\":1," +
                "\"selectionId\":\"" + SELECTION_ID + "\",\"view\":\"symbols\"}", true).join();
            listener.takeTextContaining("\"state\":\"live\"");
            rig.resolver.setContext(null);

            assertTrue(listener.takeTextContaining("\"state\":\"ended\"")
                .contains("\"tableTitle\":\"Metro / Downtown\""));
            CloseEvent close = listener.closed.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(SelectedChannelDiagnosticWebSocketTransport.SELECTION_ENDED_CLOSE_CODE,
                close.statusCode());
            assertFalse(rig.workspaceLease.isActive());
            assertEquals(0, rig.decoder.getSymbolObserverCount());
        }
    }

    @Test
    void expiredAdminSessionRevokesAndDetachesImmediately() throws Exception
    {
        try(TestRig rig = new TestRig())
        {
            TestListener listener = new TestListener();
            WebSocket socket = rig.connect(listener, true).get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            listener.takeTextContaining("\"state\":\"ready\"");
            socket.sendText("{\"action\":\"subscribe\",\"requestId\":1," +
                "\"selectionId\":\"" + SELECTION_ID + "\",\"view\":\"symbols\"}", true).join();
            listener.takeTextContaining("\"state\":\"live\"");
            rig.sessionValid.set(false);

            CloseEvent close = listener.closed.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(SelectedChannelDiagnosticWebSocketTransport.ACCESS_REVOKED_CLOSE_CODE,
                close.statusCode());
            await(() -> !rig.workspaceLease.isActive() && rig.decoder.getSymbolObserverCount() == 0);
            assertEquals(1, rig.transport.getRevokedSessionCount());
        }
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

    private static void broadcastBatch(TestDecoder decoder, float value)
    {
        for(int x = 0; x < SelectedChannelSymbolSource.BATCH_SIZE; x++)
        {
            decoder.broadcast(value);
        }
    }

    private static ComplexSamples tone(int count, int phaseOffset)
    {
        float[] i = new float[count];
        float[] q = new float[count];

        for(int x = 0; x < count; x++)
        {
            double phase = 2.0 * Math.PI * (x + phaseOffset) / 32.0;
            i[x] = (float)Math.cos(phase);
            q[x] = (float)Math.sin(phase);
        }

        return new ComplexSamples(i, q, System.currentTimeMillis());
    }

    private record CloseEvent(int statusCode, String reason)
    {
    }

    private static final class TestListener implements WebSocket.Listener
    {
        private final BlockingQueue<byte[]> mBinaryMessages = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> mTextMessages = new LinkedBlockingQueue<>();
        private final CompletableFuture<CloseEvent> closed = new CompletableFuture<>();
        private final ByteArrayOutputStream mPartialBinary = new ByteArrayOutputStream();
        private final StringBuilder mPartialText = new StringBuilder();

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
            mPartialBinary.writeBytes(bytes);

            if(last)
            {
                mBinaryMessages.add(mPartialBinary.toByteArray());
                mPartialBinary.reset();
            }

            return null;
        }

        @Override
        public synchronized CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last)
        {
            mPartialText.append(data);

            if(last)
            {
                mTextMessages.add(mPartialText.toString());
                mPartialText.setLength(0);
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
            byte[] frame = mBinaryMessages.poll(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(frame != null, "expected a diagnostic binary frame before timeout");
            return frame;
        }

        private String takeTextContaining(String expected) throws InterruptedException
        {
            long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();

            while(System.nanoTime() < deadline)
            {
                String message = mTextMessages.poll(250, TimeUnit.MILLISECONDS);

                if(message != null && message.contains(expected))
                {
                    return message;
                }
            }

            throw new AssertionError("expected diagnostic state containing " + expected);
        }
    }

    private static final class TestRig implements AutoCloseable
    {
        private static final String ADMIN_HEADER = "X-Sdrtrunk-Test-Admin";

        private final AtomicBoolean sessionValid = new AtomicBoolean(true);
        private final TestDecoder decoder = new TestDecoder();
        private final TestComplexSource sampleSource = new TestComplexSource(851_012_500L, 48_000.0);
        private final ProcessingChain processingChain;
        private final MutableContextResolver resolver;
        private final SelectedChannelDiagnosticService service;
        private final DiagnosticWorkspaceLease workspaceLease = new DiagnosticWorkspaceLease();
        private final SelectedChannelDiagnosticWebSocketTransport transport;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(TEST_TIMEOUT).build();
        private final WebApplicationService application;
        private final List<WebSocket> sockets = new ArrayList<>();

        private TestRig()
        {
            Channel channel = new Channel("Dispatch");
            processingChain = new ProcessingChain(channel, new AliasModel());
            processingChain.addModule(decoder);
            processingChain.setSource(sampleSource);
            processingChain.start();
            ChannelActivitySelectionDescriptor selection = new ChannelActivitySelectionDescriptor(SELECTION_ID,
                "table-1", "row-1", "Metro / Downtown", "Dispatch",
                ChannelActivitySelectionScope.EXACT_FREQUENCY, null, channel.getChannelID(), 851_012_500L, 1,
                "DMR");
            resolver = new MutableContextResolver(
                new LiveContext(selection, null, channel, processingChain, processingChain));
            service = new SelectedChannelDiagnosticService(resolver,
                new SelectedChannelDiagnosticService.Configuration(Duration.ofMillis(10),
                    "diagnostic-transport-test-refresh"));
            InMemoryFeatureAccessPolicy policy = InMemoryFeatureAccessPolicy.currentProfileDefaults();
            SignalSubjectResolver subjectResolver = new SignalSubjectResolver()
            {
                @Override
                public AuthorizationSubject resolve(
                    org.eclipse.jetty.websocket.server.ServerUpgradeRequest request)
                {
                    return "true".equals(request.getHeaders().get(ADMIN_HEADER)) ?
                        AuthorizationSubject.AUTHENTICATED_ADMIN : AuthorizationSubject.ANONYMOUS;
                }

                @Override
                public SignalAuthorization resolveAuthorization(
                    org.eclipse.jetty.websocket.server.ServerUpgradeRequest request)
                {
                    AuthorizationSubject subject = resolve(request);
                    return subject.isAuthenticatedAdmin() ?
                        new SignalAuthorization(subject, sessionValid::get) : SignalAuthorization.permanent(subject);
                }
            };
            transport = new SelectedChannelDiagnosticWebSocketTransport(
                new SelectedChannelDiagnosticWebSocketTransport.Configuration(Duration.ofMillis(25),
                    Duration.ofSeconds(2), Duration.ofSeconds(3), 8, "diagnostic-transport-test-"),
                service, policy, subjectResolver, SignalOriginPolicy.sameOrigin(),
                RemoteAddressAdmissionPolicy.allowAll(), workspaceLease);
            application = new WebApplicationService(WebApplicationService.Configuration.ephemeralLoopback(),
                new NotFoundHandler(), transport::configure);
            application.start();
        }

        private String origin()
        {
            URI base = application.getBaseUri();
            return base.getScheme() + "://" + base.getAuthority();
        }

        private CompletableFuture<WebSocket> connect(TestListener listener, boolean admin)
        {
            URI base = application.getBaseUri();
            URI webSocketUri = URI.create("ws://" + base.getAuthority() +
                SelectedChannelDiagnosticWebSocketTransport.PATH);
            WebSocket.Builder builder = client.newWebSocketBuilder().connectTimeout(TEST_TIMEOUT)
                .header("Origin", origin());

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
            for(WebSocket socket: List.copyOf(sockets))
            {
                socket.abort();
            }

            transport.close();
            application.close();
            service.close();
            processingChain.stop();
            client.close();
        }
    }

    private static final class TestDecoder extends FeedbackDecoder
    {
        @Override
        public String getProtocolDescription()
        {
            return "DMR test decoder";
        }

        @Override
        public DecoderType getDecoderType()
        {
            return DecoderType.DMR;
        }
    }

    private static final class TestComplexSource extends ComplexSource
    {
        private final long mFrequency;
        private final double mSampleRate;
        private Listener<ComplexSamples> mListener;

        private TestComplexSource(long frequency, double sampleRate)
        {
            mFrequency = frequency;
            mSampleRate = sampleRate;
        }

        private void emit(ComplexSamples samples)
        {
            Listener<ComplexSamples> listener = mListener;

            if(listener != null)
            {
                listener.receive(samples);
            }
        }

        @Override
        public void setListener(Listener<ComplexSamples> listener)
        {
            mListener = listener;
        }

        @Override
        public Listener<SourceEvent> getSourceEventListener()
        {
            return event -> { };
        }

        @Override
        public void setSourceEventListener(Listener<SourceEvent> listener)
        {
        }

        @Override
        public void removeSourceEventListener()
        {
        }

        @Override
        public double getSampleRate()
        {
            return mSampleRate;
        }

        @Override
        public long getFrequency()
        {
            return mFrequency;
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void start()
        {
        }

        @Override
        public void stop()
        {
        }
    }

    private static final class MutableContextResolver extends LiveContextResolver
    {
        private final AtomicReference<LiveContext> mContext;

        private MutableContextResolver(LiveContext context)
        {
            super(new ChannelProcessingManager(null, null, null, null, new UserPreferences()));
            mContext = new AtomicReference<>(context);
        }

        private void setContext(LiveContext context)
        {
            mContext.set(context);
        }

        @Override
        public Optional<LiveContext> resolve(String selectionId)
        {
            LiveContext context = mContext.get();
            return context != null && context.selectionId().equals(selectionId) ? Optional.of(context) :
                Optional.empty();
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
