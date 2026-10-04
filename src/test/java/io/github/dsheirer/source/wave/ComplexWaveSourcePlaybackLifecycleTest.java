/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.wave;

import io.github.dsheirer.source.IFrameLocationListener;
import java.io.FilterInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplexWaveSourcePlaybackLifecycleTest
{
    @TempDir Path mTemporaryDirectory;

    @Test
    void anEofReadFinishingAfterStopCannotRescheduleAnOrphanReplay() throws Exception
    {
        PausedEofSource source = new PausedEofSource(wave());
        try
        {
            source.start();
            assertTrue(source.readEntered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(250), source::stop);
            source.releaseEof.countDown();
            assertTrue(source.eofReturned.await(2, TimeUnit.SECONDS));
            assertFalse(source.restarted.await(300, TimeUnit.MILLISECONDS),
                "A canceled producer must not restart when its outstanding read reaches EOF");
        }
        finally
        {
            source.releaseEof.countDown();
            source.stop();
        }
    }

    @Test
    void aRetiredEofReadCannotReplaceSubsequentlyRestartedPlayback() throws Exception
    {
        PausedEofSource source = new PausedEofSource(wave());
        try
        {
            source.start();
            assertTrue(source.readEntered.await(2, TimeUnit.SECONDS));
            source.stop();
            source.start();
            assertTrue(source.restarted.await(2, TimeUnit.SECONDS));
            ScheduledFuture<?> current = replay(source);
            source.releaseEof.countDown();
            assertTrue(source.eofReturned.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertSame(current, replay(source), "The retired EOF callback must not replace the new producer");
            assertFalse(current.isCancelled());
            assertEquals(2_400_000, source.getSampleRate());
        }
        finally
        {
            source.releaseEof.countDown();
            source.stop();
        }
    }

    @Test
    void anInitialFrameLocationObserverCannotHoldStopOrRestartPlaybackAfterStop() throws Exception
    {
        ComplexWaveSource source = new ComplexWaveSource(wave().toFile(), true);
        CountDownLatch notificationEntered = new CountDownLatch(1);
        CountDownLatch releaseNotification = new CountDownLatch(1);
        source.setListener(new IFrameLocationListener()
        {
            @Override public void frameLocationUpdated(int location)
            {
                notificationEntered.countDown();
                awaitUninterruptibly(releaseNotification);
            }
            @Override public void frameLocationReset() { }
        });
        try(var executor = Executors.newSingleThreadExecutor())
        {
            var started = executor.submit(source::start);
            try
            {
                assertTrue(notificationEntered.await(2, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofMillis(250), source::stop);
                releaseNotification.countDown();
                started.get(2, TimeUnit.SECONDS);
                assertNull(replay(source), "Completing the stopped startup notification must not schedule playback");
                assertEquals(0, source.getSampleRate());
            }
            finally
            {
                releaseNotification.countDown();
                source.stop();
            }
        }
    }

    @Test
    void aPositiveReadFinishingAfterStopAndRestartCannotPublishIntoTheNewPlayback() throws Exception
    {
        ComplexWaveSource source = new ComplexWaveSource(wave().toFile(), true);
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        CountDownLatch readReturned = new CountDownLatch(1);
        CountDownLatch retiredBuffer = new CountDownLatch(1);
        CountDownLatch newBuffer = new CountDownLatch(1);
        source.open();
        Field inputField = ComplexWaveSource.class.getDeclaredField("mInputStream");
        inputField.setAccessible(true);
        AudioInputStream original = (AudioInputStream)inputField.get(source);
        AudioInputStream paused = new AudioInputStream(new FilterInputStream(original)
        {
            @Override public int read(byte[] bytes, int offset, int length) throws IOException
            {
                int read = super.read(bytes, offset, length);
                if(read > 0)
                {
                    bytes[offset] = 32; // Distinguish the retired read without altering the WAV on disk.
                    readEntered.countDown();
                    awaitUninterruptibly(releaseRead);
                    readReturned.countDown();
                }
                return read;
            }
        }, original.getFormat(), original.getFrameLength());
        inputField.set(source, paused);
        source.setListener(buffer ->
        {
            if(buffer.iterator().next().i()[0] != 0) retiredBuffer.countDown();
            else newBuffer.countDown();
        });
        try
        {
            source.start();
            assertTrue(readEntered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(250), source::stop);
            source.start();
            assertTrue(newBuffer.await(2, TimeUnit.SECONDS));
            ScheduledFuture<?> current = replay(source);
            releaseRead.countDown();
            assertTrue(readReturned.await(2, TimeUnit.SECONDS));
            assertFalse(retiredBuffer.await(300, TimeUnit.MILLISECONDS), "Retired samples must be discarded");
            assertSame(current, replay(source));
        }
        finally
        {
            releaseRead.countDown();
            source.stop();
        }
    }

    @Test
    void openingAnAlreadyOpenWaveDoesNotReportAFalseFrameReset() throws Exception
    {
        try(ComplexWaveSource source = new ComplexWaveSource(wave().toFile()))
        {
            AtomicInteger notifications = new AtomicInteger();
            source.setListener(new IFrameLocationListener()
            {
                @Override public void frameLocationUpdated(int location) { notifications.incrementAndGet(); }
                @Override public void frameLocationReset() { }
            });
            source.open();
            source.open();
            assertEquals(1, notifications.get());
        }
    }

    @Test
    void eofLoopReadsItsFirstBufferImmediatelyWithoutAnExtraScheduledInterval() throws Exception
    {
        try(ComplexWaveSource source = new ComplexWaveSource(wave().toFile()))
        {
            AtomicInteger buffers = new AtomicInteger();
            source.setListener(buffer -> buffers.incrementAndGet());
            source.open();
            ComplexWaveSource.ReplayController replay = source.new ReplayController(65_536);
            replay.run();
            assertEquals(1, buffers.get());
            replay.run(); // EOF must reopen and immediately deliver the next loop's first real buffer.
            assertEquals(2, buffers.get());
            assertNull(replay(source), "EOF looping must not create a replacement scheduled task");
        }
    }

    @Test
    void aRetiredProducerEnteringReadAfterRestartCannotConsumeTheReplacementStream() throws Exception
    {
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch retiredReturned = new CountDownLatch(1);
        AtomicReference<Thread> retiredThread = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger replacementReadsByRetiredProducer = new AtomicInteger();
        ComplexWaveSource source = new ComplexWaveSource(wave().toFile(), true)
        {
            @Override protected void readNext(int frames, boolean broadcast, long generation) throws IOException
            {
                if(calls.incrementAndGet() == 1)
                {
                    retiredThread.set(Thread.currentThread());
                    admitted.countDown();
                    awaitUninterruptibly(release);
                    try { super.readNext(frames, broadcast, generation); }
                    finally { retiredReturned.countDown(); }
                }
                // Keep the replacement stream open for inspection; no synthetic buffers are emitted.
            }
        };
        try
        {
            source.start();
            assertTrue(admitted.await(2, TimeUnit.SECONDS));
            source.stop();
            source.open();
            Field inputField = ComplexWaveSource.class.getDeclaredField("mInputStream");
            inputField.setAccessible(true);
            AudioInputStream replacement = (AudioInputStream)inputField.get(source);
            inputField.set(source, new AudioInputStream(new FilterInputStream(replacement)
            {
                @Override public int read(byte[] bytes, int offset, int length) throws IOException
                {
                    if(Thread.currentThread() == retiredThread.get())
                        replacementReadsByRetiredProducer.incrementAndGet();
                    return super.read(bytes, offset, length);
                }
            }, replacement.getFormat(), replacement.getFrameLength()));
            source.start();
            ScheduledFuture<?> current = replay(source);
            release.countDown();
            assertTrue(retiredReturned.await(2, TimeUnit.SECONDS));
            assertEquals(0, replacementReadsByRetiredProducer.get(), "A retired callback must not read the new file stream");
            assertSame(current, replay(source));
        }
        finally
        {
            release.countDown();
            source.stop();
        }
    }

    private static ScheduledFuture<?> replay(ComplexWaveSource source) throws Exception
    {
        Field field = ComplexWaveSource.class.getDeclaredField("mReplayController");
        field.setAccessible(true);
        return (ScheduledFuture<?>)field.get(source);
    }

    private static void awaitUninterruptibly(CountDownLatch latch)
    {
        while(latch.getCount() != 0)
        {
            try { latch.await(2, TimeUnit.SECONDS); }
            catch(InterruptedException ignored) { }
        }
    }

    private Path wave() throws IOException
    {
        ByteBuffer bytes = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(40)
            .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
            .putShort((short)1).putShort((short)2).putInt(2_400_000).putInt(9_600_000)
            .putShort((short)4).putShort((short)16)
            .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(4).putInt(0);
        Path wave = mTemporaryDirectory.resolve("paused-eof.wav");
        Files.write(wave, bytes.array());
        return wave;
    }

    private static final class PausedEofSource extends ComplexWaveSource
    {
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch releaseEof = new CountDownLatch(1);
        private final CountDownLatch eofReturned = new CountDownLatch(1);
        private final CountDownLatch restarted = new CountDownLatch(1);
        private final AtomicInteger reads = new AtomicInteger();

        private PausedEofSource(Path file) throws IOException
        {
            super(file.toFile(), true);
        }

        @Override
        protected void readNext(int frames, boolean broadcast, long generation) throws IOException
        {
            if(reads.incrementAndGet() > 1)
            {
                restarted.countDown();
                return;
            }
            readEntered.countDown();
            // Model a filesystem read that completes despite the cancellation's interrupt.
            while(releaseEof.getCount() != 0)
            {
                try { releaseEof.await(2, TimeUnit.SECONDS); }
                catch(InterruptedException ignored) { }
            }
            eofReturned.countDown();
            throw new IOException("End of file reached");
        }
    }
}
