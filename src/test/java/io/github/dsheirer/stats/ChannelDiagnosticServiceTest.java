/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.SourceEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class ChannelDiagnosticServiceTest
{
    private static final String CONFIGURATION_ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void supportsBoundedDemandSessionsAndReleasesThemOnClose()
    {
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences());
        DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
        ChannelDiagnosticService service = new ChannelDiagnosticService(manager, scheduler);
        ChannelDiagnosticService.Scope scope = new ChannelDiagnosticService.Scope(CONFIGURATION_ID, 851_012_500L,
            null);
        ChannelDiagnosticService.OpenResult first = service.tryOpen(scope);
        assertEquals(ChannelDiagnosticService.OpenStatus.OPEN, first.status());
        assertEquals("waiting", first.session().state().state());
        assertEquals("waiting", first.session().state().signalState());
        assertEquals("waiting", first.session().state().symbolsState());
        ChannelDiagnosticService.OpenResult second = service.tryOpen(scope);
        assertEquals(ChannelDiagnosticService.OpenStatus.OPEN, second.status());
        assertFalse(first.session().isClosed());
        assertEquals(2, service.activeSessionCount());
        assertEquals(0, service.activeProducerCount());
        assertTrue(service.hasBindingWorker());
        assertFalse(scheduler.hasWorker(), "scope lookup must not consume the FFT worker");
        first.session().close();
        assertEquals(1, service.activeSessionCount());
        second.session().close();
        assertEquals(0, service.activeSessionCount());
        assertFalse(service.hasBindingWorker());
        assertFalse(scheduler.hasWorker());

        service.close();
        scheduler.close();
        assertEquals(ChannelDiagnosticService.OpenStatus.CLOSED, service.tryOpen(scope).status());
    }

    @Test
    void retriesWorkerSideWhenInitialRefreshFails()
    {
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences())
        {
            @Override
            public List<ProcessingChain> getProcessingChainsByConfiguration(String configurationId, Long frequency)
            {
                calls.incrementAndGet();

                if(fail.getAndSet(false))
                {
                    throw new IllegalStateException("test failure");
                }

                return List.of();
            }
        };
        ChannelDiagnosticService service = new ChannelDiagnosticService(manager);
        ChannelDiagnosticService.Scope scope = new ChannelDiagnosticService.Scope(CONFIGURATION_ID, 851_012_500L,
            null);
        ChannelDiagnosticService.OpenResult result = service.tryOpen(scope);
        assertEquals(ChannelDiagnosticService.OpenStatus.OPEN, result.status());
        await(() -> calls.get() >= 2);
        assertEquals("waiting", result.session().state().state());
        assertEquals(1, service.activeSessionCount());
        result.session().close();
        service.close();
    }

    @Test
    void retriesTransientSignalAttachmentWithoutChangingTheProcessingChain()
    {
        TestProcessingChain chain = new TestProcessingChain();
        chain.setSource(new TestComplexSource());
        chain.failNextSignalAttachment();
        chain.setProcessing(true);
        ChannelProcessingManager manager = manager(chain);
        ChannelDiagnosticService service = new ChannelDiagnosticService(manager);
        ChannelDiagnosticService.OpenResult result = service.tryOpen(new ChannelDiagnosticService.Scope(
            CONFIGURATION_ID, 851_012_500L, null));

        try
        {
            assertEquals(ChannelDiagnosticService.OpenStatus.OPEN, result.status());
            await(() -> chain.signalAttachmentAttempts() >= 2 &&
                "live".equals(result.session().state().signalState()));
            assertEquals(1, chain.signalTapCount());
            assertEquals(1, service.activeProducerCount());
        }
        finally
        {
            result.session().close();
            service.close();
            chain.dispose();
            manager.shutdown();
        }

        assertEquals(0, chain.signalTapCount());
    }

    @Test
    void retriesTransientSymbolAttachmentWithoutChangingTheProcessingChain()
    {
        TestProcessingChain chain = new TestProcessingChain();
        TestFeedbackDecoder decoder = new TestFeedbackDecoder();
        decoder.failNextAttachment();
        chain.addModule(decoder);
        chain.setProcessing(true);
        ChannelProcessingManager manager = manager(chain);
        ChannelDiagnosticService service = new ChannelDiagnosticService(manager);
        ChannelDiagnosticService.OpenResult result = service.tryOpen(new ChannelDiagnosticService.Scope(
            CONFIGURATION_ID, 851_012_500L, null));

        try
        {
            assertEquals(ChannelDiagnosticService.OpenStatus.OPEN, result.status());
            await(() -> decoder.attachmentAttempts() >= 2 &&
                "live".equals(result.session().state().symbolsState()));
            assertEquals(1, decoder.activeObservers());
            assertEquals(1, service.activeProducerCount());
        }
        finally
        {
            result.session().close();
            service.close();
            chain.dispose();
            manager.shutdown();
        }

        assertEquals(0, decoder.activeObservers());
    }

    @Test
    void validatesExactFrequencyAndBoundedSymbolBatch()
    {
        assertThrows(IllegalArgumentException.class, () -> new ChannelDiagnosticService.Scope(
            CONFIGURATION_ID, 0, null));
        assertFalse(ChannelDiagnosticService.MAXIMUM_VISIBLE_SYMBOLS < ChannelDiagnosticService.SYMBOL_BATCH_SIZE);
    }

    @Test
    void retainsLatestSignalAndSymbolsIndependently() throws Exception
    {
        DiagnosticFrameQueue queue = new DiagnosticFrameQueue();
        DiagnosticStreamFrame signal = DiagnosticStreamFrame.float32(
            DiagnosticStreamFrame.TYPE_CHANNEL_SIGNAL, 1, 1, 10, 851_012_500L, 25_000, 1_024,
            new float[]{1.0f});
        DiagnosticStreamFrame symbols = DiagnosticStreamFrame.float32(
            DiagnosticStreamFrame.TYPE_CHANNEL_SYMBOLS, 1, 1, 11, 851_012_500L, 4_800, 0,
            new float[]{2.0f});
        DiagnosticStreamFrame nextSignal = DiagnosticStreamFrame.float32(
            DiagnosticStreamFrame.TYPE_CHANNEL_SIGNAL, 1, 2, 12, 851_012_500L, 25_000, 1_024,
            new float[]{3.0f});
        queue.offer(signal);
        queue.offer(symbols);

        assertSame(signal, queue.poll(Duration.ZERO));
        queue.offer(nextSignal);
        assertSame(symbols, queue.poll(Duration.ZERO));
        assertSame(nextSignal, queue.poll(Duration.ZERO));
        assertNull(queue.poll(Duration.ZERO));
        queue.close();
    }

    @Test
    void waitsForFramesWhenBothDiagnosticsAreIdle() throws Exception
    {
        DiagnosticFrameQueue queue = new DiagnosticFrameQueue();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);

        try
        {
            Future<DiagnosticStreamFrame> waiting = executor.submit(() ->
            {
                started.countDown();
                return queue.poll(Duration.ofSeconds(5));
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            Thread.sleep(25);
            assertFalse(waiting.isDone());
            queue.close();
            assertNull(waiting.get(1, TimeUnit.SECONDS));
        }
        finally
        {
            queue.close();
            executor.shutdownNow();
        }
    }

    private static void await(BooleanSupplier condition)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        while(System.nanoTime() < deadline)
        {
            if(condition.getAsBoolean())
            {
                return;
            }

            try
            {
                Thread.sleep(10);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }

    private static ChannelProcessingManager manager(ProcessingChain chain)
    {
        return new ChannelProcessingManager(null, null, null, new UserPreferences())
        {
            @Override
            public List<ProcessingChain> getProcessingChainsByConfiguration(String configurationId, Long frequency)
            {
                return List.of(chain);
            }
        };
    }

    private static final class TestProcessingChain extends ProcessingChain
    {
        private final AtomicBoolean mFailSignalAttachment = new AtomicBoolean();
        private final AtomicBoolean mProcessing = new AtomicBoolean();
        private final AtomicInteger mSignalAttachmentAttempts = new AtomicInteger();

        private TestProcessingChain()
        {
            super(new Channel("diagnostic test", Channel.ChannelType.STANDARD), null);
        }

        private void failNextSignalAttachment()
        {
            mFailSignalAttachment.set(true);
        }

        private void setProcessing(boolean processing)
        {
            mProcessing.set(processing);
        }

        @Override
        public boolean isProcessing()
        {
            return mProcessing != null && mProcessing.get();
        }

        @Override
        public void addModule(Module module)
        {
            if(mFailSignalAttachment != null && module != null &&
                "SignalTap".equals(module.getClass().getSimpleName()))
            {
                mSignalAttachmentAttempts.incrementAndGet();

                if(mFailSignalAttachment.compareAndSet(true, false))
                {
                    throw new IllegalStateException("transient signal attachment failure");
                }
            }

            super.addModule(module);
        }

        private int signalAttachmentAttempts()
        {
            return mSignalAttachmentAttempts.get();
        }

        private long signalTapCount()
        {
            return getModules().stream().filter(module ->
                "SignalTap".equals(module.getClass().getSimpleName())).count();
        }
    }

    private static final class TestComplexSource extends ComplexSource
    {
        private Listener<ComplexSamples> mListener;
        private Listener<SourceEvent> mSourceEventListener;

        @Override
        public void setListener(Listener<ComplexSamples> listener)
        {
            mListener = listener;
        }

        @Override
        public Listener<SourceEvent> getSourceEventListener()
        {
            return sourceEvent -> { };
        }

        @Override
        public void setSourceEventListener(Listener<SourceEvent> listener)
        {
            mSourceEventListener = listener;
        }

        @Override
        public void removeSourceEventListener()
        {
            mSourceEventListener = null;
        }

        @Override
        public double getSampleRate()
        {
            return 25_000;
        }

        @Override
        public long getFrequency()
        {
            return 851_012_500L;
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
            mListener = null;
            mSourceEventListener = null;
        }
    }

    private static final class TestFeedbackDecoder extends FeedbackDecoder
    {
        private final AtomicBoolean mFailAttachment = new AtomicBoolean();
        private final AtomicInteger mAttachmentAttempts = new AtomicInteger();
        private final AtomicInteger mActiveObservers = new AtomicInteger();

        private void failNextAttachment()
        {
            mFailAttachment.set(true);
        }

        @Override
        public synchronized void addSymbolObserver(SymbolObserver observer)
        {
            mAttachmentAttempts.incrementAndGet();

            if(mFailAttachment.compareAndSet(true, false))
            {
                throw new IllegalStateException("transient symbol attachment failure");
            }

            super.addSymbolObserver(observer);
            mActiveObservers.incrementAndGet();
        }

        @Override
        public synchronized void removeSymbolObserver(SymbolObserver observer)
        {
            super.removeSymbolObserver(observer);
            mActiveObservers.decrementAndGet();
        }

        private int attachmentAttempts()
        {
            return mAttachmentAttempts.get();
        }

        private int activeObservers()
        {
            return mActiveObservers.get();
        }

        @Override
        public String getProtocolDescription()
        {
            return "Test";
        }

        @Override
        public DecoderType getDecoderType()
        {
            return DecoderType.DMR;
        }
    }
}
