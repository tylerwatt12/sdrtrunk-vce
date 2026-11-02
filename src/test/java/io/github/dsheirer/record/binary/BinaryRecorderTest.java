/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.record.binary;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.remote.P25RemoteBitstreamPacket;
import io.github.dsheirer.source.SourceEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinaryRecorderTest
{
    private static final long FREQUENCY = 851_012_500L;
    @TempDir Path directory;

    @Test void copiesOnlyTheLocalBuffersRemainingBytesWithoutChangingItsPositionOrLimit() throws Exception
    {
        RecordingFiles files = new RecordingFiles(directory.resolve("local"));
        WriteGate gate = new WriteGate();
        BinaryRecorder recorder = recorder(files, Protocol.APCO25, 8, 1024, gate);
        byte[] backing = {10, 11, 12, 13, 14, 15};
        ByteBuffer input = ByteBuffer.wrap(backing);
        input.position(2);
        input.limit(5);
        AtomicReference<Thread> producer = new AtomicReference<>();
        recorder.start();
        try
        {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
            {
                producer.set(Thread.currentThread());
                recorder.getByteBufferListener().receive(input);
            });
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS));
            backing[2] = 99;
            assertEquals(2, input.position());
            assertEquals(5, input.limit());
            gate.release.countDown();
            finish(recorder);
            assertArrayEquals(new byte[]{12, 13, 14}, files.allBytes());
            assertTrue(files.ioThreads.stream().noneMatch(thread -> thread == producer.get()));
        }
        finally { gate.release.countDown(); finish(recorder); }
    }

    @Test void writesExactRemotePackedBytesForBothP25PhasesWithoutAddingHeaders() throws Exception
    {
        for(Protocol protocol: List.of(Protocol.APCO25, Protocol.APCO25_PHASE2))
        {
            RecordingFiles files = new RecordingFiles(directory.resolve(protocol.name()));
            BinaryRecorder recorder = recorder(files, protocol, 8, 1024, null);
            recorder.start();
            try
            {
                byte[] first = {(byte)0x88, (byte)0xF1, 0x23};
                P25RemoteBitstreamPacket packet = packet(first, 0, true);
                first[0] = 0;
                recorder.getRemoteBitstreamListener().receive(packet);
                recorder.getRemoteBitstreamListener().receive(packet(new byte[]{0x45, 0x67}, 1, false));
                finish(recorder);
                assertEquals(1, files.paths.size());
                assertArrayEquals(new byte[]{(byte)0x88, (byte)0xF1, 0x23, 0x45, 0x67}, files.allBytes());
                String filename = files.paths.getFirst().getFileName().toString();
                assertTrue(filename.contains(Long.toString(FREQUENCY)));
                assertTrue(filename.contains(protocol.getBitRate() + "BPS"));
            }
            finally { finish(recorder); }
        }
    }

    @Test void stopReturnsWithoutWaitingForDiskAndDrainsTheAcceptedTailOnItsWorker() throws Exception
    {
        RecordingFiles files = new RecordingFiles(directory.resolve("tail"));
        WriteGate gate = new WriteGate();
        BinaryRecorder recorder = recorder(files, Protocol.APCO25, 8, 1024, gate);
        recorder.start();
        try
        {
            recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{1}));
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS));
            for(int value = 2; value <= 7; value++)
                recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{(byte)value}));
            assertTimeoutPreemptively(Duration.ofSeconds(1), recorder::stop);
            CompletableFuture<Void> drained = recorder.completion();
            assertFalse(drained.isDone(), "The stopped writer still owns accepted data while disk is blocked");
            gate.release.countDown();
            drained.get(3, TimeUnit.SECONDS);
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7}, files.allBytes());
            assertTrue(files.closed.get());
        }
        finally { gate.release.countDown(); finish(recorder); }
    }

    @Test void saturatedWriterDropsWithoutBlockingAndStartsANewSegmentAfterTheLoss() throws Exception
    {
        RecordingFiles files = new RecordingFiles(directory.resolve("saturated"));
        WriteGate gate = new WriteGate();
        BinaryRecorder recorder = recorder(files, Protocol.APCO25, 2, 1024, gate);
        recorder.start();
        try
        {
            recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{1}));
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
            {
                recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{2}));
                recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{3}));
                for(int value = 0; value < 1000; value++)
                    recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{99}));
            });
            gate.release.countDown();
            await(() -> files.written.get() == 3);
            recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{4}));
            finish(recorder);
            assertEquals(2, files.paths.size());
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(files.paths.get(0)));
            assertArrayEquals(new byte[]{4}, Files.readAllBytes(files.paths.get(1)));
        }
        finally { gate.release.countDown(); finish(recorder); }
    }

    @Test void remoteDiscontinuitiesAndReconnectsKeepTheirBytesInSeparateSegments() throws Exception
    {
        RecordingFiles files = new RecordingFiles(directory.resolve("remote-boundaries"));
        BinaryRecorder recorder = recorder(files, Protocol.APCO25_PHASE2, 16, 1024, null);
        recorder.start();
        try
        {
            recorder.getRemoteBitstreamListener().receive(packet(new byte[]{1}, 0, true));
            recorder.getRemoteBitstreamListener().receive(packet(new byte[]{2}, 1, false));
            recorder.getRemoteBitstreamListener().receive(packet(new byte[]{3}, 3, true));
            recorder.getRemoteBitstreamListener().receive(packet(new byte[]{4}, 4, true));
            recorder.getRemoteBitstreamListener().receive(packet(new byte[]{5}, 0, true));
            finish(recorder);
            assertEquals(4, files.paths.size());
            assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(files.paths.get(0)));
            for(int index = 1; index < 4; index++)
                assertArrayEquals(new byte[]{(byte)(index + 2)}, Files.readAllBytes(files.paths.get(index)));
        }
        finally { finish(recorder); }
    }

    @Test void aRetuneStartsANewFileNamedForTheNewFrequencyWithoutLosingTheQueuedOldBytes() throws Exception
    {
        RecordingFiles files = new RecordingFiles(directory.resolve("retune"));
        WriteGate gate = new WriteGate();
        BinaryRecorder recorder = recorder(files, Protocol.APCO25, 8, 1024, gate);
        recorder.start();
        try
        {
            recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{1}));
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS));
            recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{2}));
            recorder.getSourceEventListener().receive(SourceEvent.frequencyChange(null, FREQUENCY + 25_000));
            recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{3}));
            gate.release.countDown();
            finish(recorder);
            assertEquals(2, files.paths.size());
            assertTrue(files.paths.get(0).getFileName().toString().contains(Long.toString(FREQUENCY)));
            assertTrue(files.paths.get(1).getFileName().toString().contains(Long.toString(FREQUENCY + 25_000)));
            assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(files.paths.get(0)));
            assertArrayEquals(new byte[]{3}, Files.readAllBytes(files.paths.get(1)));
        }
        finally { gate.release.countDown(); finish(recorder); }
    }

    @Test void sizeRolloverAndAnExistingNameNeverOverwriteARecording() throws Exception
    {
        RecordingFiles files = new RecordingFiles(directory.resolve("rollover"));
        AtomicBoolean collide = new AtomicBoolean(true);
        CopyOnWriteArrayList<Path> collision = new CopyOnWriteArrayList<>();
        BinaryRecorder recorder = new BinaryRecorder(files.directory, "test", Protocol.APCO25, 9600,
            FREQUENCY, 32, 4, path ->
            {
                if(collide.compareAndSet(true, false))
                {
                    Files.write(path, new byte[]{88}, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    collision.add(path);
                    throw new FileAlreadyExistsException(path.toString());
                }
                return files.open(path, null);
            });
        recorder.start();
        try
        {
            for(int value = 0; value < 12; value++)
                recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{(byte)value}));
            finish(recorder);
            assertEquals(3, files.paths.size());
            assertEquals(3, files.paths.stream().distinct().count());
            assertArrayEquals(new byte[]{88}, Files.readAllBytes(collision.getFirst()));
            assertArrayEquals(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, files.allBytes());
            for(Path path: files.paths) assertEquals(4, Files.size(path));
        }
        finally { finish(recorder); }
    }

    @Test void resetSplitsTheStreamWhileARepeatedFrequencyNotificationDoesNot() throws Exception
    {
        RecordingFiles files = new RecordingFiles(directory.resolve("reset"));
        BinaryRecorder recorder = recorder(files, Protocol.APCO25, 8, 1024, null);
        recorder.start();
        try
        {
            recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{1}));
            recorder.getSourceEventListener().receive(SourceEvent.frequencyChange(null, FREQUENCY));
            recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{2}));
            recorder.reset();
            recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{3}));
            finish(recorder);
            assertEquals(2, files.paths.size());
            assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(files.paths.get(0)));
            assertArrayEquals(new byte[]{3}, Files.readAllBytes(files.paths.get(1)));
        }
        finally { finish(recorder); }
    }

    @Test void aQuickStopStartKeepsThePreviousDrainSeparateFromTheNewSession() throws Exception
    {
        RecordingFiles files = new RecordingFiles(directory.resolve("restart"));
        WriteGate gate = new WriteGate();
        BinaryRecorder recorder = recorder(files, Protocol.APCO25, 8, 1024, gate);
        recorder.start();
        try
        {
            recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{1}));
            assertTrue(gate.entered.await(2, TimeUnit.SECONDS));
            recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{2}));
            recorder.stop();
            CompletableFuture<Void> prior = recorder.completion();
            assertFalse(prior.isDone());
            recorder.start();
            recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{3}));
            recorder.stop();
            CompletableFuture<Void> current = recorder.completion();
            gate.release.countDown();
            prior.get(3, TimeUnit.SECONDS);
            current.get(3, TimeUnit.SECONDS);
            assertEquals(2, files.paths.size());
            assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(files.paths.get(0)));
            assertArrayEquals(new byte[]{3}, Files.readAllBytes(files.paths.get(1)));
        }
        finally { gate.release.countDown(); finish(recorder); }
    }

    @Test void aStorageFailureAfterAPartialWriteClosesOnceAndDisablesOnlyTheRecorder() throws Exception
    {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<Path> output = new AtomicReference<>();
        AtomicReference<Thread> producer = new AtomicReference<>();
        CopyOnWriteArrayList<Thread> ioThreads = new CopyOnWriteArrayList<>();
        BinaryRecorder recorder = new BinaryRecorder(directory, "failure", Protocol.APCO25, 9600,
            FREQUENCY, 8, 1024, path ->
            {
                opens.incrementAndGet();
                ioThreads.add(Thread.currentThread());
                output.set(path);
                WritableByteChannel target = Files.newByteChannel(path,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return new WritableByteChannel()
                {
                    @Override public int write(ByteBuffer source) throws IOException
                    {
                        ioThreads.add(Thread.currentThread());
                        if(writes.incrementAndGet() > 1) throw new IOException("Synthetic storage failure");
                        int limit = source.limit();
                        source.limit(source.position() + 1);
                        try { return target.write(source); }
                        finally { source.limit(limit); }
                    }

                    @Override public boolean isOpen() { return target.isOpen(); }
                    @Override public void close() throws IOException
                    {
                        ioThreads.add(Thread.currentThread());
                        closes.incrementAndGet();
                        target.close();
                    }
                };
            });
        recorder.start();
        try
        {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
            {
                producer.set(Thread.currentThread());
                recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{1, 2, 3}));
            });
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> recorder.completion().get(3, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals(1, opens.get());
            assertEquals(2, writes.get());
            assertEquals(1, closes.get());
            assertArrayEquals(new byte[]{1}, Files.readAllBytes(output.get()));
            assertTrue(ioThreads.stream().noneMatch(thread -> thread == producer.get()));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
            {
                for(int index = 0; index < 1000; index++)
                    recorder.getByteBufferListener().receive(ByteBuffer.wrap(new byte[]{99}));
                recorder.stop();
            });
            assertEquals(1, opens.get(), "A failed recorder must not repeatedly reopen storage");
            assertEquals(2, writes.get(), "Later callbacks must not retry failed writes");
            assertEquals(1, closes.get());
        }
        finally { recorder.stop(); }
    }

    private static BinaryRecorder recorder(RecordingFiles files, Protocol protocol, int capacity,
                                            int maximumBytes, WriteGate gate)
    {
        return new BinaryRecorder(files.directory, "test", protocol, protocol.getBitRate(), FREQUENCY,
            capacity, maximumBytes, path -> files.open(path, gate));
    }

    private static P25RemoteBitstreamPacket packet(byte[] bytes, long sequence, boolean gap)
    {
        return new P25RemoteBitstreamPacket(bytes, 1_000 + sequence, sequence, gap);
    }

    private static void finish(BinaryRecorder recorder) throws Exception
    {
        recorder.stop();
        recorder.completion().get(4, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for recorder worker");
    }

    private static final class WriteGate
    {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean first = new AtomicBoolean(true);
    }

    private static final class RecordingFiles
    {
        final Path directory;
        final CopyOnWriteArrayList<Path> paths = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<Thread> ioThreads = new CopyOnWriteArrayList<>();
        final AtomicInteger written = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();

        RecordingFiles(Path directory) throws IOException
        {
            this.directory = Files.createDirectories(directory);
        }

        WritableByteChannel open(Path path, WriteGate gate) throws IOException
        {
            ioThreads.add(Thread.currentThread());
            WritableByteChannel target = Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            paths.add(path);
            return new WritableByteChannel()
            {
                @Override public int write(ByteBuffer source) throws IOException
                {
                    ioThreads.add(Thread.currentThread());
                    if(gate != null && gate.first.compareAndSet(true, false))
                    {
                        gate.entered.countDown();
                        try
                        {
                            if(!gate.release.await(5, TimeUnit.SECONDS)) throw new IOException("Synthetic blocked writer timed out");
                        }
                        catch(InterruptedException interrupted)
                        {
                            Thread.currentThread().interrupt();
                            throw new IOException("Synthetic blocked writer interrupted");
                        }
                    }
                    // Exercise channels that accept less than the entire supplied buffer.
                    int limit = source.limit();
                    source.limit(Math.min(limit, source.position() + 1));
                    int count;
                    try { count = target.write(source); }
                    finally { source.limit(limit); }
                    written.addAndGet(count);
                    return count;
                }

                @Override public boolean isOpen() { return target.isOpen(); }
                @Override public void close() throws IOException { target.close(); closed.set(true); }
            };
        }

        byte[] allBytes() throws IOException
        {
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            for(Path path: paths) joined.write(Files.readAllBytes(path));
            return joined.toByteArray();
        }
    }
}
