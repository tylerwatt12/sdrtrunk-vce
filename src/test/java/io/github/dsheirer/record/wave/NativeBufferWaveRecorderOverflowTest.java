/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.record.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeBufferWaveRecorderOverflowTest
{
    @TempDir
    Path mTempDirectory;

    @Test
    void tunerProducerDoesNotWaitForContendedRecorderControlLock() throws Exception
    {
        NativeBufferWaveRecorder recorder = new NativeBufferWaveRecorder(2_400_000,
            mTempDirectory.resolve("contended_control").toString(), (count, file, size) -> { });
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch producerFinished = new CountDownLatch(1);
        recorder.start();
        Thread lockHolder = Thread.ofPlatform().start(() -> {
            synchronized(recorder)
            {
                lockHeld.countDown();
                try
                {
                    releaseLock.await(2, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try
        {
            assertTrue(lockHeld.await(2, TimeUnit.SECONDS));
            Thread.ofPlatform().start(() -> {
                recorder.receive(new TestBuffer(null, null));
                producerFinished.countDown();
            });
            assertTrue(producerFinished.await(500, TimeUnit.MILLISECONDS),
                "The tuner producer must not acquire the recorder's control lock");
        }
        finally
        {
            releaseLock.countDown();
            lockHolder.join(2_000);
            recorder.stop();
        }
    }

    @Test
    void slowWriterCannotBlockReceiverProducerOrGrowTheRecordingQueue() throws Exception
    {
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch overflowNotified = new CountDownLatch(1);
        AtomicReference<String> recordingFile = new AtomicReference<>();
        AtomicReference<String> overflowThread = new AtomicReference<>();
        AtomicReference<String> failureReason = new AtomicReference<>();
        NativeBufferWaveRecorder recorder = new NativeBufferWaveRecorder(2_400_000,
            mTempDirectory.resolve("debug_baseband").toString(),
            (count, file, size) -> recordingFile.set(file));
        recorder.setFailureHandler(reason -> {
            failureReason.set(reason);
            overflowThread.set(Thread.currentThread().getName());
            overflowNotified.countDown();
        });

        try
        {
            recorder.start();
            assertTrue(recorder.isRunning());
            recorder.receive(new TestBuffer(writerEntered, releaseWriter));
            assertTrue(writerEntered.await(2, TimeUnit.SECONDS));

            long start = System.nanoTime();
            for(int index = 0; index < 4 * recorder.getMaximumPendingBuffers(); index++)
            {
                recorder.receive(new TestBuffer(null, null));
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 500,
                "The producer must not wait for a blocked recorder writer");
            assertEquals(64, recorder.getMaximumPendingBuffers());
            assertTrue(recorder.getDroppedBufferCount() > 0);
            assertTrue(overflowNotified.await(2, TimeUnit.SECONDS));
            assertTrue(failureReason.get().contains("bounded recording handoff could not keep up"));
            assertFalse(recorder.isRunning(), "Overflow must abort the diagnostic recording");
            assertFalse(Thread.currentThread().getName().equals(overflowThread.get()),
                "Overflow handling must not execute on the receiver producer thread");
        }
        finally
        {
            releaseWriter.countDown();
            recorder.stop();
        }

        Path incomplete = Path.of(recordingFile.get().replaceFirst("\\.wav$", ".incomplete.wav"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while(!Files.exists(incomplete) && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(incomplete), "An overflowed recording must be labeled incomplete on disk");
    }

    @Test
    void sampleRateChangeAbortsWithoutWaitingForBlockedWriter() throws Exception
    {
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch failureNotified = new CountDownLatch(1);
        AtomicReference<String> recordingFile = new AtomicReference<>();
        AtomicReference<String> failureReason = new AtomicReference<>();
        NativeBufferWaveRecorder recorder = new NativeBufferWaveRecorder(2_400_000,
            mTempDirectory.resolve("sample_rate_change").toString(),
            (count, file, size) -> recordingFile.set(file));
        recorder.setFailureHandler(reason -> {
            failureReason.set(reason);
            failureNotified.countDown();
        });

        try
        {
            recorder.start();
            recorder.receive(new TestBuffer(writerEntered, releaseWriter));
            assertTrue(writerEntered.await(2, TimeUnit.SECONDS));

            long start = System.nanoTime();
            recorder.setSampleRate(1_000_000);
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 500,
                "A sample-rate event must not wait for a blocked recorder writer");
            assertTrue(failureNotified.await(2, TimeUnit.SECONDS));
            assertTrue(failureReason.get().contains("sample rate changed"));
            assertFalse(recorder.isRunning());
        }
        finally
        {
            releaseWriter.countDown();
            recorder.stop();
        }

        Path incomplete = Path.of(recordingFile.get().replaceFirst("\\.wav$", ".incomplete.wav"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while(!Files.exists(incomplete) && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(incomplete), "A sample-rate change must not leave a misleading complete WAV");
    }

    @Test
    void stopAndRestartKeepTheOldBlockedWriterIsolated() throws Exception
    {
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CopyOnWriteArrayList<String> files = new CopyOnWriteArrayList<>();
        NativeBufferWaveRecorder recorder = new NativeBufferWaveRecorder(2_400_000,
            mTempDirectory.resolve("restart").toString(), (count, file, size) -> files.add(file));

        try
        {
            recorder.start();
            recorder.receive(new TestBuffer(writerEntered, releaseWriter));
            assertTrue(writerEntered.await(2, TimeUnit.SECONDS));

            long start = System.nanoTime();
            recorder.stop();
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 500,
                "Stop must not wait for the old writer's disk operation");
            recorder.start();
            assertTrue(recorder.isRunning());
            assertEquals(2, files.size());
            assertFalse(files.get(0).equals(files.get(1)), "A quick restart needs a distinct file name");
            recorder.receive(new TestBuffer(null, null));
            releaseWriter.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while(!Files.exists(Path.of(files.get(0))) && System.nanoTime() < deadline)
            {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(Path.of(files.get(0))));
            assertTrue(recorder.isRunning(), "Retired writer completion must not stop the new run");
        }
        finally
        {
            releaseWriter.countDown();
            recorder.stop();
        }
    }

    @Test
    void normalStopDrainsAcceptedBuffersWithoutWaitingForTheWriter() throws Exception
    {
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicReference<String> recordingFile = new AtomicReference<>();
        NativeBufferWaveRecorder recorder = new NativeBufferWaveRecorder(2_400_000,
            mTempDirectory.resolve("normal_stop").toString(),
            (count, file, size) -> recordingFile.set(file));

        try
        {
            recorder.start();
            recorder.receive(new TestBuffer(writerEntered, releaseWriter, true));
            assertTrue(writerEntered.await(2, TimeUnit.SECONDS));
            for(int index = 0; index < 3; index++)
            {
                recorder.receive(new TestBuffer(null, null, true));
            }

            long start = System.nanoTime();
            recorder.stop();
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 500,
                "Manual stop must not wait for a blocked diagnostic writer");
        }
        finally
        {
            releaseWriter.countDown();
        }

        Path wave = Path.of(recordingFile.get());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while(Files.size(wave) < 60 && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }
        assertEquals(60, Files.size(wave), "The current and three queued two-sample buffers must be present");
        assertFalse(Files.exists(Path.of(recordingFile.get().replaceFirst("\\.wav$", ".incomplete.wav"))));
    }

    private record TestBuffer(CountDownLatch entered, CountDownLatch release, boolean withSamples)
        implements INativeBuffer
    {
        private TestBuffer(CountDownLatch entered, CountDownLatch release)
        {
            this(entered, release, false);
        }

        @Override
        public Iterator<ComplexSamples> iterator()
        {
            return Collections.emptyIterator();
        }

        @Override
        public Iterator<InterleavedComplexSamples> iteratorInterleaved()
        {
            if(entered != null)
            {
                entered.countDown();
            }
            if(release != null)
            {
                try
                {
                    release.await(2, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
            return withSamples ? List.of(new InterleavedComplexSamples(new float[]{0.25f, -0.25f}, 0)).iterator() :
                Collections.emptyIterator();
        }

        @Override
        public int sampleCount()
        {
            return withSamples ? 1 : 0;
        }

        @Override
        public long getTimestamp()
        {
            return 0;
        }
    }
}
