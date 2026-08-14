/*
 * ****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.dsheirer.buffer.FloatNativeBuffer;
import io.github.dsheirer.spectrum.DFTSize;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DiagnosticTransportTest
{
    @Test
    void constructsFftOnlyOnTheDiagnosticWorker() throws Exception
    {
        DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
        Thread producerThread = Thread.currentThread();
        DemandDftProcessor processor = new DemandDftProcessor(scheduler, DFTSize.FFT00512, 20,
            (timestamp, values) -> {});

        try
        {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

            while(processor.initializationThread() == null && System.nanoTime() < deadline)
            {
                Thread.onSpinWait();
            }

            assertNotSame(producerThread, processor.initializationThread());
            assertTrue(processor.initializationThread().getName().contains("diagnostic FFT"));
        }
        finally
        {
            processor.close();
            scheduler.close();
        }
    }

    @Test
    void saturatedIngressDropsImmediatelyWithoutRunningWorkOnTheProducer() throws Exception
    {
        DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
        CountDownLatch workersStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        Runnable blockingTask = () ->
        {
            workersStarted.countDown();

            try
            {
                releaseWorker.await();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        };
        DiagnosticFftScheduler.Task firstBlocker = scheduler.scheduleWithFixedDelay(blockingTask, 20);
        assertTrue(workersStarted.await(1, TimeUnit.SECONDS));
        DemandDftProcessor processor = new DemandDftProcessor(scheduler, DFTSize.FFT00512, 20,
            (timestamp, values) -> {});
        FloatNativeBuffer buffer = new FloatNativeBuffer(new float[1_024], 1, 1);

        try
        {
            assertTimeoutPreemptively(Duration.ofMillis(250), () ->
            {
                for(int x = 0; x < 100; x++)
                {
                    processor.receive(buffer, x + 1);
                }
            });
            assertEquals(92, processor.droppedBufferCount());
        }
        finally
        {
            processor.close();
            releaseWorker.countDown();
            firstBlocker.close();
            scheduler.close();
        }
    }

    @Test
    void constructsTheTunerFftOnlyOnTheDiagnosticWorker() throws Exception
    {
        DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
        Thread producerThread = Thread.currentThread();
        TunerDiagnosticService.TunerFftProcessor processor = new TunerDiagnosticService.TunerFftProcessor(scheduler,
            100_000_000L, 10_000_000L, ignored -> {});

        try
        {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

            while(processor.initializationThread() == null && System.nanoTime() < deadline)
            {
                Thread.onSpinWait();
            }

            assertNotSame(producerThread, processor.initializationThread());
            assertTrue(processor.initializationThread().getName().contains("diagnostic FFT"));
        }
        finally
        {
            processor.close();
            scheduler.close();
        }
    }

    @Test
    void saturatedTunerIngressAndCloseNeverWaitForTheWorker() throws Exception
    {
        DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        DiagnosticFftScheduler.Task blocker = scheduler.scheduleWithFixedDelay(() ->
        {
            workerStarted.countDown();
            awaitUninterruptibly(releaseWorker);
        }, 20);
        assertTrue(workerStarted.await(1, TimeUnit.SECONDS));
        TunerDiagnosticService.TunerFftProcessor processor = new TunerDiagnosticService.TunerFftProcessor(scheduler,
            100_000_000L, 10_000_000L, ignored -> {});
        FloatNativeBuffer buffer = new FloatNativeBuffer(new float[4_096], 1, 1);

        try
        {
            assertTimeoutPreemptively(Duration.ofMillis(250), () ->
            {
                long configuration = processor.configuration();

                for(int x = 0; x < 256; x++)
                {
                    processor.receive(buffer, x + 1, configuration);
                }
            });
            assertEquals(128, processor.droppedBufferCount());
            assertTimeoutPreemptively(Duration.ofMillis(250), processor::close,
                "closing a diagnostic lease must not wait for worker-owned FFT or queue state");
        }
        finally
        {
            processor.close();
            releaseWorker.countDown();
            blocker.close();
            scheduler.close();
        }
    }

    @Test
    void bindingLifecycleUsesASeparateFailureDomainFromDiagnostics() throws Exception
    {
        DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
        ChannelDiagnosticBindingScheduler bindingScheduler = new ChannelDiagnosticBindingScheduler();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch diagnosticProgress = new CountDownLatch(3);
        ChannelDiagnosticBindingScheduler.Task blocker = bindingScheduler.scheduleWithFixedDelay(() ->
        {
            blocked.countDown();

            try
            {
                releaseBlocker.await();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        }, 4);

        assertTrue(blocked.await(1, TimeUnit.SECONDS));
        DiagnosticFftScheduler.Task diagnostic = scheduler.scheduleWithFixedDelay(diagnosticProgress::countDown, 60);

        try
        {
            assertTrue(diagnosticProgress.await(1, TimeUnit.SECONDS),
                "one blocked binding lookup must not freeze unrelated tuner/channel diagnostics");
        }
        finally
        {
            releaseBlocker.countDown();
            diagnostic.close();
            blocker.close();
            bindingScheduler.close();
            scheduler.close();
        }
    }

    @Test
    void fftReplacementWaitsForABlockedPreviousDemandCycle() throws Exception
    {
        DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
        CountDownLatch oldStarted = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch replacementRan = new CountDownLatch(1);
        AtomicBoolean oldRunning = new AtomicBoolean();
        AtomicBoolean overlapped = new AtomicBoolean();
        DiagnosticFftScheduler.Task oldTask = scheduler.scheduleWithFixedDelay(() ->
        {
            oldRunning.set(true);
            oldStarted.countDown();

            try
            {
                awaitUninterruptibly(releaseOld);
            }
            finally
            {
                oldRunning.set(false);
            }
        }, 60);
        DiagnosticFftScheduler.Task replacement = null;

        try
        {
            assertTrue(oldStarted.await(1, TimeUnit.SECONDS));
            oldTask.close();
            replacement = scheduler.scheduleWithFixedDelay(() ->
            {
                overlapped.set(oldRunning.get());
                replacementRan.countDown();
            }, 60);

            assertFalse(replacementRan.await(100, TimeUnit.MILLISECONDS),
                "a replacement must remain behind a blocked task from the previous demand cycle");
            releaseOld.countDown();
            assertTrue(replacementRan.await(1, TimeUnit.SECONDS));
            assertFalse(overlapped.get());
        }
        finally
        {
            releaseOld.countDown();
            oldTask.close();

            if(replacement != null)
            {
                replacement.close();
            }

            scheduler.close();
        }
    }

    @Test
    void bindingReplacementWaitsForABlockedPreviousDemandCycle() throws Exception
    {
        ChannelDiagnosticBindingScheduler scheduler = new ChannelDiagnosticBindingScheduler();
        CountDownLatch oldStarted = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch replacementRan = new CountDownLatch(1);
        AtomicBoolean oldRunning = new AtomicBoolean();
        AtomicBoolean overlapped = new AtomicBoolean();
        ChannelDiagnosticBindingScheduler.Task oldTask = scheduler.scheduleWithFixedDelay(() ->
        {
            oldRunning.set(true);
            oldStarted.countDown();

            try
            {
                awaitUninterruptibly(releaseOld);
            }
            finally
            {
                oldRunning.set(false);
            }
        }, 10);
        ChannelDiagnosticBindingScheduler.Task replacement = null;

        try
        {
            assertTrue(oldStarted.await(1, TimeUnit.SECONDS));
            oldTask.close();
            replacement = scheduler.scheduleWithFixedDelay(() ->
            {
                overlapped.set(oldRunning.get());
                replacementRan.countDown();
            }, 10);

            assertFalse(replacementRan.await(100, TimeUnit.MILLISECONDS),
                "a replacement must remain behind a blocked binding from the previous demand cycle");
            releaseOld.countDown();
            assertTrue(replacementRan.await(1, TimeUnit.SECONDS));
            assertFalse(overlapped.get());
        }
        finally
        {
            releaseOld.countDown();
            oldTask.close();

            if(replacement != null)
            {
                replacement.close();
            }

            scheduler.close();
        }
    }

    @Test
    void createsAndRemovesTheOnlyWorkerWithDemand() throws Exception
    {
        DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
        CountDownLatch ran = new CountDownLatch(1);

        assertFalse(scheduler.hasWorker());
        assertEquals(0, scheduler.activeTaskCount());

        DiagnosticFftScheduler.Task task = scheduler.scheduleWithFixedDelay(ran::countDown, 20);
        assertTrue(scheduler.hasWorker());
        assertEquals(1, scheduler.activeTaskCount());
        assertTrue(ran.await(1, TimeUnit.SECONDS));

        task.close();
        assertFalse(scheduler.hasWorker());
        assertEquals(0, scheduler.activeTaskCount());
        scheduler.close();
    }

    @Test
    void continuesPeriodicDiagnosticsAfterAnObserverFailure() throws Exception
    {
        DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        DiagnosticFftScheduler.Task task = scheduler.scheduleWithFixedDelay(() ->
        {
            if(calls.incrementAndGet() == 1)
            {
                throw new IllegalStateException("injected diagnostic failure");
            }

            recovered.countDown();
        }, 60);

        try
        {
            assertTrue(recovered.await(2, TimeUnit.SECONDS));
            assertTrue(calls.get() >= 2);
        }
        finally
        {
            task.close();
            scheduler.close();
        }
    }

    @Test
    void publishesFreshSamplesWithoutTheOldOneFrameDelay() throws Exception
    {
        DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
        CountDownLatch published = new CountDownLatch(1);
        AtomicLong observedAt = new AtomicLong();
        AtomicLong completedAt = new AtomicLong();
        DemandDftProcessor processor = new DemandDftProcessor(scheduler, DFTSize.FFT00512, 20,
            (timestamp, values) ->
            {
                observedAt.set(timestamp);
                completedAt.set(System.currentTimeMillis());
                assertEquals(DFTSize.FFT00512.getSize(), values.length);
                published.countDown();
            });

        try
        {
            float[] samples = new float[4_096];
            long before = System.currentTimeMillis();
            processor.receive(new FloatNativeBuffer(samples, 1, 1));
            processor.receive(new FloatNativeBuffer(samples, 1, 1));

            assertTrue(published.await(2, TimeUnit.SECONDS));
            assertTrue(observedAt.get() >= before);
            assertTrue(completedAt.get() - observedAt.get() < 500,
                "A fresh diagnostic FFT should stay within the browser latency budget before transport");
        }
        finally
        {
            processor.close();
            scheduler.close();
        }

        assertFalse(scheduler.hasWorker());
    }

    @Test
    void rejectsALateBufferFromThePreviousTuningEpoch() throws Exception
    {
        DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
        CountDownLatch workersStarted = new CountDownLatch(1);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        Runnable blockingTask = () ->
        {
            workersStarted.countDown();

            try
            {
                releaseWorkers.await();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        };
        DiagnosticFftScheduler.Task firstBlocker = scheduler.scheduleWithFixedDelay(blockingTask, 20);
        assertTrue(workersStarted.await(1, TimeUnit.SECONDS));
        CountDownLatch published = new CountDownLatch(1);
        AtomicLong observedAt = new AtomicLong();
        AtomicLong publishedConfiguration = new AtomicLong();
        DemandDftProcessor processor = new DemandDftProcessor(scheduler, DFTSize.FFT00512, 20,
            (timestamp, values, configuration) ->
            {
                observedAt.set(timestamp);
                publishedConfiguration.set(configuration);
                published.countDown();
            });

        try
        {
            long oldConfiguration = processor.configuration();
            long newConfiguration = processor.requestReset();
            FloatNativeBuffer oldBuffer = new FloatNativeBuffer(new float[1_024], 1, 1);
            FloatNativeBuffer newBuffer = new FloatNativeBuffer(new float[1_024], 2, 2);
            FloatNativeBuffer followingBuffer = new FloatNativeBuffer(new float[1_024], 3, 3);

            //Model a callback that captured the old epoch before the retune but completed its offer afterward.
            processor.receive(oldBuffer, 111, oldConfiguration);
            processor.receive(newBuffer, 222, newConfiguration);
            //NativeBufferManager promotes a completed producer batch when the following tuner buffer arrives.
            processor.receive(followingBuffer, 333, newConfiguration);
            releaseWorkers.countDown();

            assertTrue(published.await(2, TimeUnit.SECONDS));
            assertEquals(222, observedAt.get());
            assertEquals(newConfiguration, publishedConfiguration.get());
        }
        finally
        {
            releaseWorkers.countDown();
            processor.close();
            firstBlocker.close();
            scheduler.close();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch)
    {
        boolean interrupted = false;

        while(latch.getCount() > 0)
        {
            try
            {
                latch.await();
            }
            catch(InterruptedException exception)
            {
                interrupted = true;
            }
        }

        if(interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void encodesTheFixedLittleEndianWireHeaderAndSanitizesValues()
    {
        DiagnosticStreamFrame frame = DiagnosticStreamFrame.float32(
            DiagnosticStreamFrame.TYPE_TUNER_FFT, 7, 9, 100, 773_106_250L, 10_000_000L, 4_096,
            new float[]{Float.NaN, -250.0f, 30.0f, -42.5f});
        ByteBuffer encoded = ByteBuffer.wrap(frame.encoded()).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(DiagnosticStreamFrame.MAGIC, encoded.getInt());
        assertEquals(DiagnosticStreamFrame.VERSION, Byte.toUnsignedInt(encoded.get()));
        assertEquals(DiagnosticStreamFrame.TYPE_TUNER_FFT, Byte.toUnsignedInt(encoded.get()));
        assertEquals(DiagnosticStreamFrame.HEADER_BYTES, Short.toUnsignedInt(encoded.getShort()));
        assertEquals(4 * Float.BYTES, encoded.getInt());
        assertEquals(4, encoded.getInt());
        assertEquals(7, encoded.getLong());
        assertEquals(9, encoded.getLong());
        assertEquals(100, encoded.getLong());
        assertTrue(encoded.getLong() >= 100);
        assertEquals(773_106_250L, encoded.getLong());
        assertEquals(10_000_000, encoded.getInt());
        assertEquals(4_096, encoded.getInt());
        assertEquals(0, encoded.getInt());
        assertEquals(4_096, encoded.getInt());
        assertEquals(-196.0f, encoded.getFloat());
        assertEquals(-196.0f, encoded.getFloat());
        assertEquals(20.0f, encoded.getFloat());
        assertEquals(-42.5f, encoded.getFloat());
    }

    @Test
    void encodesViewportMetadataAtTheExtendedHeaderOffsets()
    {
        DiagnosticStreamFrame frame = DiagnosticStreamFrame.float32(
            DiagnosticStreamFrame.TYPE_TUNER_FFT, 3, 4, 100, 100_000_000L, 8_192_000L, 32_768,
            14_336, 4_096, new float[4_096]);
        ByteBuffer encoded = ByteBuffer.wrap(frame.encoded()).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(72, Short.toUnsignedInt(encoded.getShort(6)));
        assertEquals(32_768, encoded.getInt(60));
        assertEquals(14_336, encoded.getInt(64));
        assertEquals(4_096, encoded.getInt(68));
        assertEquals(4_096 * Float.BYTES, encoded.getInt(8));
        assertEquals(4_096, encoded.getInt(12));
    }

    @Test
    void retainsOnlyTheLatestFramePerTypeAndWakesPollersOnClose() throws Exception
    {
        DiagnosticFrameQueue queue = new DiagnosticFrameQueue();
        DiagnosticStreamFrame first = signal(1);
        DiagnosticStreamFrame latest = signal(2);

        queue.offer(first);
        queue.offer(latest);

        assertSame(latest, queue.poll(Duration.ZERO));
        assertNull(queue.poll(Duration.ZERO));
        queue.close();
        assertNull(queue.poll(Duration.ofSeconds(1)));
    }

    private static DiagnosticStreamFrame signal(long sequence)
    {
        return DiagnosticStreamFrame.float32(DiagnosticStreamFrame.TYPE_CHANNEL_SIGNAL, 1, sequence,
            System.currentTimeMillis(), 851_012_500L, 25_000L, 512, new float[]{-50.0f});
    }
}
