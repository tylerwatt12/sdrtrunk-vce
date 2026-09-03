/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.dsp.filter.channelizer;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.buffer.INativeBufferProvider;
import io.github.dsheirer.dsp.filter.channelizer.output.ChannelOutputProcessor;
import io.github.dsheirer.dsp.filter.channelizer.output.IPolyphaseChannelOutputProcessor;
import io.github.dsheirer.dsp.filter.channelizer.output.OneChannelOutputProcessor;
import io.github.dsheirer.dsp.filter.design.FilterDesignException;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.heartbeat.HeartbeatManager;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelOutputProcessorBackpressureTest
{
    @Test
    void boundedOutputQueueReleasesOverflowAndStoppedBuffers() throws Exception
    {
        BlockingOutputProcessor processor = new BlockingOutputProcessor();
        int overflow = 4;
        int queued = processor.queueCapacity() + overflow;
        int total = queued + 1;
        AtomicInteger recycled = new AtomicInteger();
        CountDownLatch allRecycled = new CountDownLatch(total);

        try
        {
            processor.start();
            processor.receiveChannelResults(buffer(recycled, allRecycled));
            assertTrue(processor.processingStarted.await(5, TimeUnit.SECONDS));

            for(int x = 0; x < queued; x++)
            {
                processor.receiveChannelResults(buffer(recycled, allRecycled));
            }

            assertEquals(overflow, recycled.get());
            processor.stop();
            assertEquals(queued, recycled.get());
            processor.releaseProcessing.countDown();
            assertTrue(allRecycled.await(5, TimeUnit.SECONDS));
            assertEquals(total, recycled.get());
        }
        finally
        {
            processor.releaseProcessing.countDown();
            processor.stop();
        }
    }

    @Test
    void batchArenaPreallocatesAndRecyclesOnlyCompleteBatches() throws Exception
    {
        ComplexPolyphaseChannelizerM2 channelizer = new ComplexPolyphaseChannelizerM2(50_000.0, 12);
        List<ComplexPolyphaseChannelizerM2.ChannelResultsBuffer> retained = drainBatchArena(channelizer);
        assertEquals(ComplexPolyphaseChannelizerM2.CHANNEL_RESULTS_ARENA_CAPACITY - 1,
            retained.size(), "one preallocated batch is reserved for the producer");
        java.util.IdentityHashMap<float[], Boolean> rows = new java.util.IdentityHashMap<>();

        for(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer batch: retained)
        {
            for(int x = 0; x < ComplexPolyphaseChannelizerM2.CHANNEL_RESULTS_BATCH_CAPACITY; x++)
            {
                float[] row = batch.nextResult();
                assertEquals(channelizer.getSubChannelCount(), row.length);
                assertNull(rows.put(row, Boolean.TRUE), "each result row must have distinct permanent storage");
            }
        }

        assertNull(channelizer.acquireChannelResultsBuffer(), "the fixed arena must never allocate an overflow batch");
        retained.forEach(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer::recycleNow);
        List<ComplexPolyphaseChannelizerM2.ChannelResultsBuffer> recovered = drainBatchArena(channelizer);
        assertEquals(ComplexPolyphaseChannelizerM2.CHANNEL_RESULTS_ARENA_CAPACITY - 1,
            recovered.size(), "every released batch must return to the same bounded arena");
        assertTrue(recovered.containsAll(retained));
        recovered.forEach(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer::recycleNow);
    }

    @Test
    void outputWarmupLeavesTheAssemblerCleanAndNeverDispatchesSyntheticSamples()
    {
        OneChannelOutputProcessor processor = new OneChannelOutputProcessor(25_000.0, List.of(0), 1.0f,
            new HeartbeatManager(), "output warm-up test");
        AtomicInteger delivered = new AtomicInteger();
        processor.setListener(ignored -> delivered.incrementAndGet());

        processor.warmUp(2);

        assertEquals(0, delivered.get(), "zero-valued warm-up samples must never reach the decoder listener");
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer oneResult =
            new ComplexPolyphaseChannelizerM2.ChannelResultsBuffer(1, ignored -> {});
        oneResult.add(new float[2]);
        processor.process(oneResult);
        assertEquals(0, delivered.get(), "warm-up must leave the output assembler at an empty buffer boundary");
        assertEquals(0, processor.getQueueStatus().droppedBatches());
    }

    @Test
    void saturatedBatchArenaDropsACompleteBatchWithoutAllocatingOrBlocking() throws Exception
    {
        ComplexPolyphaseChannelizerM2 channelizer = new ComplexPolyphaseChannelizerM2(50_000.0, 12);
        List<ComplexPolyphaseChannelizerM2.ChannelResultsBuffer> retained = drainBatchArena(channelizer);
        Thread producer = new Thread(() -> channelizer.receive(
            new InterleavedComplexSamples(new float[(ComplexPolyphaseChannelizerM2.CHANNEL_RESULTS_BATCH_CAPACITY + 1) *
                channelizer.getChannelCount()], 0)), "saturated channelizer producer");
        producer.start();
        producer.join(TimeUnit.SECONDS.toMillis(2));

        assertFalse(producer.isAlive(), "arena exhaustion must never wait for a downstream consumer");
        assertEquals(1, channelizer.getQueueStatus().droppedBatches(),
            "crossing one full-batch boundary must count exactly one explicit loss");
        assertNull(channelizer.acquireChannelResultsBuffer(), "arena exhaustion must not allocate a fallback batch");
        retained.forEach(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer::recycleNow);
        List<ComplexPolyphaseChannelizerM2.ChannelResultsBuffer> recovered = drainBatchArena(channelizer);
        assertEquals(ComplexPolyphaseChannelizerM2.CHANNEL_RESULTS_ARENA_CAPACITY - 1, recovered.size());
        recovered.forEach(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer::recycleNow);
    }

    @Test
    void fixedRateChannelizersOwnOnlyCorrectlySizedBatches() throws Exception
    {
        ComplexPolyphaseChannelizerM2 first = new ComplexPolyphaseChannelizerM2(50_000.0, 12);
        ComplexPolyphaseChannelizerM2 replacement = new ComplexPolyphaseChannelizerM2(100_000.0, 12);
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer firstBatch = first.acquireChannelResultsBuffer();
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer replacementBatch =
            replacement.acquireChannelResultsBuffer();

        assertNotEquals(first.getSubChannelCount(), replacement.getSubChannelCount());
        assertEquals(first.getSubChannelCount(), firstBatch.nextResult().length);
        assertEquals(replacement.getSubChannelCount(), replacementBatch.nextResult().length);
        firstBatch.recycleNow();
        replacementBatch.recycleNow();
    }

    @Test
    void sharedBatchReturnsOnlyAfterItsFinalConsumerReleasesIt() throws Exception
    {
        ComplexPolyphaseChannelizerM2 channelizer = new ComplexPolyphaseChannelizerM2(50_000.0, 12);
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer shared = channelizer.acquireChannelResultsBuffer();
        shared.prepareForConsumers(2);
        List<ComplexPolyphaseChannelizerM2.ChannelResultsBuffer> retained = drainBatchArena(channelizer);
        assertNull(channelizer.acquireChannelResultsBuffer());

        shared.release();
        assertNull(channelizer.acquireChannelResultsBuffer(),
            "the first consumer must not make a shared batch reusable");
        shared.release();
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer returned = channelizer.acquireChannelResultsBuffer();
        assertSame(shared, returned, "the final consumer must return the exact batch to the arena once");

        returned.recycleNow();
        retained.forEach(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer::recycleNow);
    }

    @Test
    void ifftFailureRecyclesItsBatch() throws Exception
    {
        ComplexPolyphaseChannelizerM2 channelizer = new ComplexPolyphaseChannelizerM2(50_000.0, 12);
        Field dispatcherField = ComplexPolyphaseChannelizerM2.class.getDeclaredField("mIFFTProcessorDispatcher");
        dispatcherField.setAccessible(true);
        ComplexPolyphaseChannelizerM2.IFFTProcessorDispatcher dispatcher =
            (ComplexPolyphaseChannelizerM2.IFFTProcessorDispatcher)dispatcherField.get(channelizer);
        Field fftField = dispatcher.getClass().getDeclaredField("mFFT");
        fftField.setAccessible(true);
        fftField.set(dispatcher, null);
        CountDownLatch recycled = new CountDownLatch(1);
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer batch =
            new ComplexPolyphaseChannelizerM2.ChannelResultsBuffer(1, ignored -> recycled.countDown());
        batch.add(new float[channelizer.getSubChannelCount()]);

        try
        {
            channelizer.start();
            dispatcher.receive(batch);
            assertTrue(recycled.await(5, TimeUnit.SECONDS),
                "an IFFT exception must not permanently lose its reusable batch");
        }
        finally
        {
            channelizer.stop();
        }
    }

    @Test
    void oneThrowingChannelCannotLeakOrBlockTheRemainingConsumers() throws Exception
    {
        DispatchTestChannelizer channelizer = new DispatchTestChannelizer();
        ChannelCalculator calculator = new ChannelCalculator(50_000.0, 2, 100_000_000L, 2.0);
        ThrowingChannelSource throwing = new ThrowingChannelSource(calculator);
        AtomicInteger delivered = new AtomicInteger();
        ReleasingChannelSource releasing = new ReleasingChannelSource(calculator, delivered);
        AtomicInteger recycled = new AtomicInteger();
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer batch =
            new ComplexPolyphaseChannelizerM2.ChannelResultsBuffer(1, ignored -> recycled.incrementAndGet());
        channelizer.addChannel(throwing);
        channelizer.addChannel(releasing);

        try
        {
            channelizer.dispatchForTest(batch);
            assertEquals(1, delivered.get(), "dispatch must continue after another channel throws");
            assertEquals(1, recycled.get(), "every consumer share must be released exactly once");
        }
        finally
        {
            channelizer.removeChannel(throwing);
            channelizer.removeChannel(releasing);
            throwing.stopOutputProcessorForRemoval();
            releasing.stopOutputProcessorForRemoval();
        }
    }

    @Test
    void pipelineDropSnapshotNeverRegressesDuringChannelRemoval() throws Exception
    {
        PolyphaseChannelManager manager = new PolyphaseChannelManager(new EmptyNativeBufferProvider(),
            100_000_000L, 50_000.0);

        try
        {
            atomic(manager, "mLastStableChannelOutputDrops").set(7);
            atomic(manager, "mRetiredChannelOutputDrops").set(3);
            atomic(manager, "mChannelLifecycleVersion").set(1);
            assertEquals(7, manager.getPipelineStatus().channelDroppedBatches(),
                "an in-progress removal must retain the last stable aggregate");

            atomic(manager, "mRetiredChannelOutputDrops").set(9);
            atomic(manager, "mChannelLifecycleVersion").set(2);
            assertEquals(9, manager.getPipelineStatus().channelDroppedBatches(),
                "the next stable snapshot must publish the retired cumulative total");
        }
        finally
        {
            manager.dispose();
        }
    }

    @Test
    void pipelineIfftDropSnapshotNeverRegressesDuringChannelizerReplacement() throws Exception
    {
        PolyphaseChannelManager manager = new PolyphaseChannelManager(new EmptyNativeBufferProvider(),
            100_000_000L, 50_000.0);

        try
        {
            atomic(manager, "mLastStableIfftDrops").set(7);
            atomic(manager, "mRetiredIfftDrops").set(3);
            atomic(manager, "mIfftLifecycleVersion").set(1);
            assertEquals(7, manager.getPipelineStatus().ifftDroppedBatches(),
                "an in-progress channelizer replacement must retain the last stable aggregate");

            atomic(manager, "mRetiredIfftDrops").set(9);
            atomic(manager, "mIfftLifecycleVersion").set(2);
            assertEquals(9, manager.getPipelineStatus().ifftDroppedBatches(),
                "the next stable snapshot must publish the retired cumulative total");
        }
        finally
        {
            manager.dispose();
        }
    }

    @Test
    void pipelineStatusNeverWaitsForTheChannelizerLifecycleLock() throws Exception
    {
        PolyphaseChannelManager manager = new PolyphaseChannelManager(new EmptyNativeBufferProvider(),
            100_000_000L, 50_000.0);
        Field lockField = PolyphaseChannelManager.class.getDeclaredField("mChannelizerLock");
        lockField.setAccessible(true);
        Object channelizerLock = lockField.get(manager);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        Thread holder = new Thread(() ->
        {
            synchronized(channelizerLock)
            {
                lockHeld.countDown();

                try
                {
                    releaseLock.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        }, "channelizer lifecycle lock holder");
        AtomicReference<PolyphaseChannelManager.PipelineStatus> result = new AtomicReference<>();
        Thread observer = new Thread(() -> result.set(manager.getPipelineStatus()), "pipeline status observer");

        try
        {
            holder.start();
            assertTrue(lockHeld.await(5, TimeUnit.SECONDS));
            observer.start();
            observer.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(observer.isAlive(), "health observation must never wait for the receiver lifecycle lock");
            assertTrue(result.get() != null);
        }
        finally
        {
            releaseLock.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(5));
            observer.join(TimeUnit.SECONDS.toMillis(5));
            manager.dispose();
        }
    }

    @Test
    void pipelineRetiresDropsFromAnActuallyReplacedChannelizer() throws Exception
    {
        EmptyNativeBufferProvider provider = new EmptyNativeBufferProvider();
        PolyphaseChannelManager manager = new PolyphaseChannelManager(provider, 100_000_000L, 50_000.0);
        Field channelizerField = PolyphaseChannelManager.class.getDeclaredField("mPolyphaseChannelizer");
        channelizerField.setAccessible(true);
        FixedDropChannelizer previous = new FixedDropChannelizer(100_000.0, 7);
        channelizerField.set(manager, previous);
        PolyphaseChannelSource source = (PolyphaseChannelSource)manager.getChannel(
            new TunerChannel(100_000_000L, 12_500), "ifft replacement retirement test");

        try
        {
            source.start();
            assertNotSame(previous, channelizerField.get(manager));
            assertEquals(7, manager.getPipelineStatus().ifftDroppedBatches(),
                "replacing a channelizer must retain its cumulative IFFT overflow count");
        }
        finally
        {
            source.stop();
            manager.dispose();
        }
    }

    private static List<ComplexPolyphaseChannelizerM2.ChannelResultsBuffer> drainBatchArena(
        ComplexPolyphaseChannelizerM2 channelizer)
    {
        List<ComplexPolyphaseChannelizerM2.ChannelResultsBuffer> batches = new java.util.ArrayList<>();
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer batch;

        while((batch = channelizer.acquireChannelResultsBuffer()) != null)
        {
            batches.add(batch);
        }

        return batches;
    }

    @Test
    void channelSourcePreservesDropsWhenItsOutputProcessorIsReplaced() throws Exception
    {
        ChannelCalculator calculator = new ChannelCalculator(50_000.0, 2, 100_000_000L, 2.0);
        PolyphaseChannelSource source = new PolyphaseChannelSource(new TunerChannel(100_000_000L, 12_500),
            calculator, new SynthesisFilterManager(), ignored -> {}, "channel source health test", null);

        try
        {
            Field processor = PolyphaseChannelSource.class.getDeclaredField("mPolyphaseChannelOutputProcessor");
            processor.setAccessible(true);
            IPolyphaseChannelOutputProcessor original = (IPolyphaseChannelOutputProcessor)processor.get(source);
            original.stop();
            FixedDropOutputProcessor retired = new FixedDropOutputProcessor(7);
            processor.set(source, retired);
            source.doUpdateOutputProcessor(calculator, new SynthesisFilterManager());
            assertNotSame(retired, processor.get(source), "the test must exercise an actual processor replacement");
            assertTrue(source.getOutputQueueStatus().droppedBatches() >= 7,
                "replacing the live processor must retain its cumulative overflow count");
        }
        finally
        {
            source.stopOutputProcessorForRemoval();
        }
    }

    @Test
    void failedPendingOutputRebindReleasesItsConsumerShare() throws Exception
    {
        ChannelCalculator calculator = new ChannelCalculator(50_000.0, 2, 100_000_000L, 2.0);
        PolyphaseChannelSource source = new PolyphaseChannelSource(new TunerChannel(100_000_000L, 12_500),
            calculator, new SynthesisFilterManager(), ignored -> {}, "failed output rebind test", null);
        ChannelCalculator incompatible = new ChannelCalculator(50_000.0, 2, 200_000_000L, 2.0);
        assertThrows(IllegalArgumentException.class,
            () -> incompatible.getChannelIndexes(source.getTunerChannel()));
        source.updateOutputProcessor(incompatible, new SynthesisFilterManager());
        AtomicInteger recycled = new AtomicInteger();
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer batch =
            new ComplexPolyphaseChannelizerM2.ChannelResultsBuffer(1, ignored -> recycled.incrementAndGet());
        batch.prepareForConsumers(1);

        try
        {
            source.receiveChannelResults(batch);
            assertEquals(1, recycled.get(),
                "a failed pending rebind must release the channel's batch share exactly once");
        }
        finally
        {
            source.stopOutputProcessorForRemoval();
        }
    }

    @Test
    void channelSourceStopWinsAConcurrentOutputProcessorStart() throws Exception
    {
        ChannelCalculator calculator = new ChannelCalculator(50_000.0, 2, 100_000_000L, 2.0);
        PolyphaseChannelSource source = new PolyphaseChannelSource(new TunerChannel(100_000_000L, 12_500),
            calculator, new SynthesisFilterManager(), ignored -> {}, "channel source start race test", null);
        Field processorField = PolyphaseChannelSource.class.getDeclaredField("mPolyphaseChannelOutputProcessor");
        processorField.setAccessible(true);
        IPolyphaseChannelOutputProcessor original = (IPolyphaseChannelOutputProcessor)processorField.get(source);
        original.stop();
        RacingOutputProcessor racing = new RacingOutputProcessor();
        processorField.set(source, racing);
        Thread starter = new Thread(source::start, "channel source start race");

        try
        {
            starter.start();
            assertTrue(racing.mStartEntered.await(5, TimeUnit.SECONDS));
            source.stopOutputProcessorForRemoval();
            racing.mAllowStart.countDown();
            starter.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(starter.isAlive());
            assertFalse(racing.mRunning.get(), "a processor started concurrently with removal must be stopped again");
            assertTrue(racing.mStopCount.get() >= 2,
                "the terminal post-start check must close the stop-before-start interleaving");
        }
        finally
        {
            racing.mAllowStart.countDown();
            source.stopOutputProcessorForRemoval();
            starter.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void channelSourceCompensatesAProducerStartThatFinishesAfterTerminalStop() throws Exception
    {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch allowStart = new CountDownLatch(1);
        AtomicInteger activeRegistrations = new AtomicInteger();
        AtomicInteger stopRequests = new AtomicInteger();
        Listener<SourceEvent> producer = event -> {
            switch(event.getEvent())
            {
                case REQUEST_START_SAMPLE_STREAM:
                    startEntered.countDown();

                    try
                    {
                        allowStart.await(5, TimeUnit.SECONDS);
                    }
                    catch(InterruptedException exception)
                    {
                        Thread.currentThread().interrupt();
                    }

                    activeRegistrations.incrementAndGet();
                    break;
                case REQUEST_STOP_SAMPLE_STREAM:
                    stopRequests.incrementAndGet();
                    activeRegistrations.updateAndGet(count -> Math.max(0, count - 1));
                    break;
                default:
                    break;
            }
        };
        ChannelCalculator calculator = new ChannelCalculator(50_000.0, 2, 100_000_000L, 2.0);
        PolyphaseChannelSource source = new PolyphaseChannelSource(new TunerChannel(100_000_000L, 12_500),
            calculator, new SynthesisFilterManager(), producer, "channel source producer race test", null);
        Thread starter = new Thread(source::start, "channel source producer start race");

        try
        {
            starter.start();
            assertTrue(startEntered.await(5, TimeUnit.SECONDS));
            source.stopOutputProcessorForRemoval();
            allowStart.countDown();
            starter.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(starter.isAlive());
            assertEquals(1, stopRequests.get(), "a late producer registration must receive a compensating stop");
            assertEquals(0, activeRegistrations.get(), "terminal removal must not leave a producer registered");
        }
        finally
        {
            allowStart.countDown();
            source.stopOutputProcessorForRemoval();
            starter.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void channelManagerRejectsAStaleStartAfterSourceRemoval() throws Exception
    {
        EmptyNativeBufferProvider provider = new EmptyNativeBufferProvider();
        PolyphaseChannelManager manager = new PolyphaseChannelManager(provider, 100_000_000L, 50_000.0);
        PolyphaseChannelSource source = (PolyphaseChannelSource)manager.getChannel(
            new TunerChannel(100_000_000L, 12_500), "stale source registration test");
        Field listenerField = PolyphaseChannelManager.class.getDeclaredField("mChannelSourceEventListener");
        listenerField.setAccessible(true);
        Listener<SourceEvent> managerListener = (Listener<SourceEvent>)listenerField.get(manager);
        Field processorField = PolyphaseChannelSource.class.getDeclaredField("mPolyphaseChannelOutputProcessor");
        processorField.setAccessible(true);
        IPolyphaseChannelOutputProcessor original = (IPolyphaseChannelOutputProcessor)processorField.get(source);
        original.stop();
        processorField.set(source, new FixedDropOutputProcessor(7));

        try
        {
            source.start();
            assertTrue(provider.hasBufferListeners());
            source.stop();
            assertEquals(0, manager.getTunerChannelCount());
            assertEquals(7, manager.getPipelineStatus().channelDroppedBatches(),
                "normal source removal must retire its final channel-output overflow count");
            managerListener.receive(SourceEvent.startSampleStreamRequest(source));
            assertEquals(0, manager.getTunerChannelCount(), "a retired source must not be restored to the manager");
            assertFalse(provider.hasBufferListeners(), "a stale start must not restart the tuner sample stream");
        }
        finally
        {
            manager.dispose();
        }
    }

    @Test
    void channelCountEventsAreBroadcastAfterChannelizerLockIsReleased() throws Exception
    {
        EmptyNativeBufferProvider provider = new EmptyNativeBufferProvider();
        PolyphaseChannelManager manager = new PolyphaseChannelManager(provider, 100_000_000L, 50_000.0);
        Field lockField = PolyphaseChannelManager.class.getDeclaredField("mChannelizerLock");
        lockField.setAccessible(true);
        Object channelizerLock = lockField.get(manager);
        AtomicBoolean broadcastWhileLocked = new AtomicBoolean();
        AtomicInteger channelCountEvents = new AtomicInteger();
        manager.addSourceEventListener(event ->
        {
            if(event.getEvent() == SourceEvent.Event.NOTIFICATION_CHANNEL_COUNT_CHANGE)
            {
                channelCountEvents.incrementAndGet();
                broadcastWhileLocked.compareAndSet(false, Thread.holdsLock(channelizerLock));
            }
        });
        PolyphaseChannelSource source = (PolyphaseChannelSource)manager.getChannel(
            new TunerChannel(100_000_000L, 12_500), "channel-count lock-order regression");

        try
        {
            assertTrue(source != null);
            source.start();
            source.stop();
            assertEquals(2, channelCountEvents.get(), "channel start and stop must each publish a count update");
            assertFalse(broadcastWhileLocked.get(),
                "external listeners must never run while the channelizer lifecycle lock is held");
        }
        finally
        {
            if(source != null)
            {
                source.stopOutputProcessorForRemoval();
            }

            manager.dispose();
        }
    }

    @Test
    void stopAllRejectsAndDisposesAChannelWhoseConstructionWasAlreadyInFlight() throws Exception
    {
        BlockingAdmissionManager manager = new BlockingAdmissionManager(new EmptyNativeBufferProvider(),
            100_000_000L, 50_000.0);
        AtomicReference<PolyphaseChannelSource> result = new AtomicReference<>();
        Thread allocator = new Thread(() -> result.set((PolyphaseChannelSource)manager.getChannel(
            new TunerChannel(100_000_000L, 12_500), "channel admission shutdown race")),
            "channel admission shutdown race");

        try
        {
            allocator.start();
            assertTrue(manager.mConstructed.await(5, TimeUnit.SECONDS));
            manager.stopAllChannels();
            manager.mAllowAdmission.countDown();
            allocator.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(allocator.isAlive());
            assertNull(result.get(), "a source completed after the stop snapshot must not be admitted");
            assertEquals(0, manager.getTunerChannelCount());
            assertTrue(manager.mTentativeSource.get().isOutputProcessorStopping(),
                "the rejected source dispatcher must be terminally stopped");
        }
        finally
        {
            manager.mAllowAdmission.countDown();
            allocator.join(TimeUnit.SECONDS.toMillis(5));
            manager.dispose();
        }
    }

    @Test
    void failedChannelizerReplacementRejectsAndCleansTheTentativeSource() throws Exception
    {
        EmptyNativeBufferProvider provider = new EmptyNativeBufferProvider();
        FailingReplacementManager manager = new FailingReplacementManager(provider, 100_000_000L, 50_000.0);
        Field channelizerField = PolyphaseChannelManager.class.getDeclaredField("mPolyphaseChannelizer");
        channelizerField.setAccessible(true);
        FixedDropChannelizer previous = new FixedDropChannelizer(100_000.0, 7);
        channelizerField.set(manager, previous);
        PolyphaseChannelSource source = (PolyphaseChannelSource)manager.getChannel(
            new TunerChannel(100_000_000L, 12_500), "failed replacement cleanup test");

        try
        {
            source.start();
            assertSame(previous, channelizerField.get(manager),
                "a failed replacement must not publish null or discard the prior channelizer");
            assertEquals(0, manager.getTunerChannelCount(),
                "the source must be removed when no compatible channelizer can be configured");
            assertTrue(source.isOutputProcessorStopping());
            assertFalse(provider.hasBufferListeners());
        }
        finally
        {
            source.stopOutputProcessorForRemoval();
            manager.dispose();
        }
    }

    private static AtomicLong atomic(PolyphaseChannelManager manager, String name) throws Exception
    {
        Field field = PolyphaseChannelManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return (AtomicLong)field.get(manager);
    }

    private static ComplexPolyphaseChannelizerM2.ChannelResultsBuffer buffer(AtomicInteger recycled,
                                                                               CountDownLatch allRecycled)
    {
        ComplexPolyphaseChannelizerM2.ChannelResultsBuffer buffer =
            new ComplexPolyphaseChannelizerM2.ChannelResultsBuffer(1, ignored -> {
                recycled.incrementAndGet();
                allRecycled.countDown();
            });
        buffer.add(new float[2]);
        buffer.prepareForConsumers(1);
        return buffer;
    }

    private static class BlockingOutputProcessor extends ChannelOutputProcessor
    {
        private final CountDownLatch processingStarted = new CountDownLatch(1);
        private final CountDownLatch releaseProcessing = new CountDownLatch(1);

        private BlockingOutputProcessor()
        {
            super(1, new HeartbeatManager(), "channel output backpressure test");
        }

        private int queueCapacity()
        {
            return CHANNEL_RESULTS_QUEUE_CAPACITY;
        }

        @Override
        public void process(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer channelResultsBuffer)
        {
            processingStarted.countDown();

            try
            {
                releaseProcessing.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public String getStateDescription()
        {
            return "test";
        }

        @Override
        public void setFrequencyOffset(long frequency)
        {
        }

        @Override
        public void setPolyphaseChannelIndices(List<Integer> indexes)
        {
        }

        @Override
        public void setSynthesisFilter(float[] filter)
        {
        }
    }

    private static class EmptyNativeBufferProvider implements INativeBufferProvider
    {
        private final AtomicBoolean mHasListener = new AtomicBoolean();

        @Override
        public void addBufferListener(Listener<INativeBuffer> listener)
        {
            mHasListener.set(true);
        }

        @Override
        public void removeBufferListener(Listener<INativeBuffer> listener)
        {
            mHasListener.set(false);
        }

        @Override
        public boolean hasBufferListeners()
        {
            return mHasListener.get();
        }
    }

    private static class BlockingAdmissionManager extends PolyphaseChannelManager
    {
        private final CountDownLatch mConstructed = new CountDownLatch(1);
        private final CountDownLatch mAllowAdmission = new CountDownLatch(1);
        private final AtomicReference<PolyphaseChannelSource> mTentativeSource = new AtomicReference<>();

        private BlockingAdmissionManager(INativeBufferProvider provider, long frequency, double sampleRate)
        {
            super(provider, frequency, sampleRate);
        }

        @Override
        PolyphaseChannelSource createChannelSource(TunerChannel tunerChannel, String threadName)
        {
            PolyphaseChannelSource source = super.createChannelSource(tunerChannel, threadName);
            mTentativeSource.set(source);
            mConstructed.countDown();

            try
            {
                mAllowAdmission.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }

            return source;
        }
    }

    private static class FailingReplacementManager extends PolyphaseChannelManager
    {
        private FailingReplacementManager(INativeBufferProvider provider, long frequency, double sampleRate)
        {
            super(provider, frequency, sampleRate);
        }

        @Override
        ComplexPolyphaseChannelizerM2 createChannelizer(double sampleRate) throws FilterDesignException
        {
            throw new FilterDesignException("intentional replacement failure");
        }
    }

    private static class FixedDropOutputProcessor implements IPolyphaseChannelOutputProcessor
    {
        private final long mDrops;

        private FixedDropOutputProcessor(long drops)
        {
            mDrops = drops;
        }

        @Override public String getStateDescription() { return "test"; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public void receiveChannelResults(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer buffer) {}
        @Override public void setListener(Listener<ComplexSamples> listener) {}
        @Override public void setFrequencyOffset(long frequency) {}
        @Override public int getInputChannelCount() { return Integer.MAX_VALUE; }
        @Override public void setPolyphaseChannelIndices(List<Integer> indexes) {}
        @Override public int getPolyphaseChannelIndexCount() { return Integer.MAX_VALUE; }
        @Override public ChannelOutputProcessor.QueueStatus getQueueStatus()
        {
            return new ChannelOutputProcessor.QueueStatus(0, 0, 8, mDrops);
        }
        @Override public long getDroppedBatchCount() { return mDrops; }
        @Override public void setSynthesisFilter(float[] filter) {}
        @Override public void dispose() {}
    }

    private static class RacingOutputProcessor extends FixedDropOutputProcessor
    {
        private final CountDownLatch mStartEntered = new CountDownLatch(1);
        private final CountDownLatch mAllowStart = new CountDownLatch(1);
        private final AtomicBoolean mRunning = new AtomicBoolean();
        private final AtomicInteger mStopCount = new AtomicInteger();

        private RacingOutputProcessor()
        {
            super(0);
        }

        @Override
        public void start()
        {
            mStartEntered.countDown();

            try
            {
                mAllowStart.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }

            mRunning.set(true);
        }

        @Override
        public void stop()
        {
            mStopCount.incrementAndGet();
            mRunning.set(false);
        }
    }

    private static class FixedDropChannelizer extends ComplexPolyphaseChannelizerM2
    {
        private final long mDrops;

        private FixedDropChannelizer(double sampleRate, long drops) throws Exception
        {
            super(sampleRate, 9);
            mDrops = drops;
        }

        @Override
        long getDroppedBatchCount()
        {
            return mDrops;
        }
    }

    private static class DispatchTestChannelizer extends ComplexPolyphaseChannelizerM2
    {
        private DispatchTestChannelizer() throws Exception
        {
            super(50_000.0, 12);
        }

        private void dispatchForTest(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer buffer)
        {
            dispatch(buffer);
        }
    }

    private static class ThrowingChannelSource extends PolyphaseChannelSource
    {
        private ThrowingChannelSource(ChannelCalculator calculator)
        {
            super(new TunerChannel(100_000_000L, 12_500), calculator, new SynthesisFilterManager(), ignored -> {},
                "throwing channel source", null);
        }

        @Override
        public void receiveChannelResults(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer buffer)
        {
            throw new IllegalStateException("intentional test failure");
        }
    }

    private static class ReleasingChannelSource extends PolyphaseChannelSource
    {
        private final AtomicInteger mDelivered;

        private ReleasingChannelSource(ChannelCalculator calculator, AtomicInteger delivered)
        {
            super(new TunerChannel(100_000_000L, 12_500), calculator, new SynthesisFilterManager(), ignored -> {},
                "releasing channel source", null);
            mDelivered = delivered;
        }

        @Override
        public void receiveChannelResults(ComplexPolyphaseChannelizerM2.ChannelResultsBuffer buffer)
        {
            mDelivered.incrementAndGet();
            buffer.release();
        }
    }
}
