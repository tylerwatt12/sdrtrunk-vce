/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.spectrum.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SpectrumStreamServiceTest
{
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);

    @Test
    void oneAndTenSubscribersShareOneProducerAndOneFrameInstance() throws Exception
    {
        assertSharedProducerFanOut(1);
        assertSharedProducerFanOut(10);
    }

    @Test
    void boundsSubscriberCount()
    {
        ManualSpectrumFrameSource source = new ManualSpectrumFrameSource();

        try(SpectrumStreamService service = service(source, 1, Duration.ZERO))
        {
            SpectrumStreamService.Subscription subscription = service.trySubscribe().orElseThrow();
            assertTrue(service.trySubscribe().isEmpty());
            assertEquals(1, service.getSubscriberCount());
            subscription.close();
        }
    }

    @Test
    void slowSubscriberKeepsLatestFrameAndCountsDroppedFrames() throws Exception
    {
        ManualSpectrumFrameSource source = new ManualSpectrumFrameSource();

        try(SpectrumStreamService service = service(source, 1, Duration.ZERO);
            SpectrumStreamService.Subscription subscription = service.trySubscribe().orElseThrow())
        {
            source.emit(frame(1));
            source.emit(frame(2));
            source.emit(frame(3));

            assertEquals(2, subscription.getDroppedFrameCount());
            assertEquals(3, subscription.poll(Duration.ZERO).getSequence());
            assertNull(subscription.poll(Duration.ZERO));
            assertEquals(3, service.getPublishedFrameCount());
        }
    }

    @Test
    void stopsSourceOnlyAfterLastSubscriberGrace() throws Exception
    {
        ManualSpectrumFrameSource source = new ManualSpectrumFrameSource();

        try(SpectrumStreamService service = service(source, 2, Duration.ofMillis(50)))
        {
            SpectrumStreamService.Subscription first = service.trySubscribe().orElseThrow();
            SpectrumStreamService.Subscription second = service.trySubscribe().orElseThrow();
            first.close();
            assertTrue(source.isRunning());
            second.close();
            assertTrue(source.isRunning());
            await(() -> !source.isRunning(), Duration.ofSeconds(1));
            assertEquals(1, source.getStopCount());
            assertEquals(1, service.getSourceStopCount());
        }
    }

    @Test
    void reconnectWithinGraceKeepsSourceAndReconnectAfterStopRestartsIt() throws Exception
    {
        ManualSpectrumFrameSource source = new ManualSpectrumFrameSource();

        try(SpectrumStreamService service = service(source, 1, Duration.ofMillis(100)))
        {
            SpectrumStreamService.Subscription first = service.trySubscribe().orElseThrow();
            first.close();

            SpectrumStreamService.Subscription quickReconnect = service.trySubscribe().orElseThrow();
            assertEquals(1, source.getStartCount());
            assertEquals(0, source.getStopCount());
            SpectrumFrame quickReconnectFrame = frame(10);
            source.emit(quickReconnectFrame);
            assertSame(quickReconnectFrame, quickReconnect.poll(POLL_TIMEOUT));
            quickReconnect.close();

            await(() -> !source.isRunning(), Duration.ofSeconds(1));
            SpectrumStreamService.Subscription laterReconnect = service.trySubscribe().orElseThrow();
            assertEquals(2, source.getStartCount());
            assertEquals(1, source.getStopCount());
            SpectrumFrame laterReconnectFrame = frame(11);
            source.emit(laterReconnectFrame);
            assertSame(laterReconnectFrame, laterReconnect.poll(POLL_TIMEOUT));
            laterReconnect.close();
        }
    }

    @Test
    void closeTerminatesServiceAndSyntheticProducerExecutors() throws Exception
    {
        SyntheticSpectrumFrameSource source = new SyntheticSpectrumFrameSource(
            new SyntheticSpectrumFrameSource.Configuration(1, 851_000_000L, 10_000_000L, 64,
                Duration.ofMillis(5), "spectrum-test-producer"));
        SpectrumStreamService service = service(source, 10, Duration.ofMillis(20));
        SpectrumStreamService.Subscription subscription = service.trySubscribe().orElseThrow();

        assertTrue(subscription.poll(POLL_TIMEOUT) != null);
        assertTrue(source.getProducedFrameCount() >= 1);
        assertEquals(1, source.getStartCount());

        service.close();

        assertTrue(subscription.isClosed());
        assertFalse(source.isRunning());
        assertTrue(source.isExecutorTerminated());
        assertTrue(service.isLifecycleExecutorTerminated());
    }

    private static void assertSharedProducerFanOut(int subscriberCount) throws Exception
    {
        ManualSpectrumFrameSource source = new ManualSpectrumFrameSource();

        try(SpectrumStreamService service = service(source, subscriberCount, Duration.ZERO))
        {
            List<SpectrumStreamService.Subscription> subscriptions = new ArrayList<>();

            for(int x = 0; x < subscriberCount; x++)
            {
                subscriptions.add(service.trySubscribe().orElseThrow());
            }

            assertEquals(1, source.getStartCount());
            assertEquals(1, service.getSourceStartCount());
            SpectrumFrame sharedFrame = frame(subscriberCount);
            source.emit(sharedFrame);

            for(SpectrumStreamService.Subscription subscription: subscriptions)
            {
                assertSame(sharedFrame, subscription.poll(POLL_TIMEOUT));
                subscription.close();
            }
        }
    }

    private static SpectrumStreamService service(SpectrumFrameSource source, int maximumSubscribers, Duration grace)
    {
        return new SpectrumStreamService(
            new SpectrumStreamService.Configuration(maximumSubscribers, grace, "spectrum-test-lifecycle"), source);
    }

    private static SpectrumFrame frame(long sequence)
    {
        return SpectrumFrame.float32(SpectrumFrame.FLAG_CAPTURE_TIMESTAMP_VALID, 1, sequence, System.nanoTime(),
            1_770_000_000_000_000_000L + sequence, 851_000_000L, 10_000_000L,
            new float[]{-110.0f, -90.0f});
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws Exception
    {
        long deadline = System.nanoTime() + timeout.toNanos();

        while(!condition.getAsBoolean())
        {
            if(System.nanoTime() >= deadline)
            {
                throw new AssertionError("Condition was not satisfied before timeout");
            }

            Thread.sleep(5);
        }
    }

    private static final class ManualSpectrumFrameSource implements SpectrumFrameSource
    {
        private final AtomicBoolean mRunning = new AtomicBoolean();
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private final AtomicLong mStartCount = new AtomicLong();
        private final AtomicLong mStopCount = new AtomicLong();
        private volatile Consumer<SpectrumFrame> mConsumer;

        @Override
        public void start(Consumer<SpectrumFrame> frameConsumer)
        {
            if(mClosed.get())
            {
                throw new IllegalStateException("Manual source is closed");
            }

            mConsumer = frameConsumer;
            mRunning.set(true);
            mStartCount.incrementAndGet();
        }

        void emit(SpectrumFrame frame)
        {
            Consumer<SpectrumFrame> consumer = mConsumer;

            if(!mRunning.get() || consumer == null)
            {
                throw new IllegalStateException("Manual source is not running");
            }

            consumer.accept(frame);
        }

        @Override
        public void stop()
        {
            if(mRunning.compareAndSet(true, false))
            {
                mConsumer = null;
                mStopCount.incrementAndGet();
            }
        }

        @Override
        public boolean isRunning()
        {
            return mRunning.get();
        }

        long getStartCount()
        {
            return mStartCount.get();
        }

        long getStopCount()
        {
            return mStopCount.get();
        }

        @Override
        public void close()
        {
            stop();
            mClosed.set(true);
        }
    }
}
