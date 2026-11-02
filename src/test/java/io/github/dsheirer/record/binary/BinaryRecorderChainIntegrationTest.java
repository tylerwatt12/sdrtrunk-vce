/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.record.binary;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.remote.IP25RemoteBitstreamListener;
import io.github.dsheirer.remote.P25RemoteBitstreamPacket;
import io.github.dsheirer.remote.RemoteP25BitstreamSource;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.config.SourceConfigRemote;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryRecorderChainIntegrationTest
{
    private static final long FREQUENCY = 851_012_500L;
    @TempDir Path directory;

    @Test
    void remoteChainRecordsEachPacketOnceAndKeepsOtherConsumersRunningWhileAnotherRecorderIsBlocked() throws Exception
    {
        for(boolean phase2: List.of(false, true))
        {
            Protocol protocol = phase2 ? Protocol.APCO25_PHASE2 : Protocol.APCO25;
            Path healthyDirectory = Files.createDirectories(directory.resolve(protocol.name()).resolve("healthy"));
            Path blockedDirectory = Files.createDirectories(directory.resolve(protocol.name()).resolve("blocked"));
            CountDownLatch blockedOpen = new CountDownLatch(1), releaseDisk = new CountDownLatch(1);
            AtomicReference<Thread> diskThread = new AtomicReference<>();
            BinaryRecorder healthy = new BinaryRecorder(healthyDirectory, "healthy", protocol, FREQUENCY);
            BinaryRecorder blocked = new BinaryRecorder(blockedDirectory, "blocked", protocol, protocol.getBitRate(),
                FREQUENCY, 2, 1024, path ->
                {
                    diskThread.set(Thread.currentThread());
                    blockedOpen.countDown();
                    try
                    {
                        if(!releaseDisk.await(10, TimeUnit.SECONDS)) throw new IOException("Test disk timed out");
                    }
                    catch(InterruptedException exception)
                    {
                        Thread.currentThread().interrupt();
                        throw new IOException("Test disk interrupted");
                    }
                    return Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                });
            PacketConsumer consumer = new PacketConsumer(9);
            Channel channel = new Channel("Remote recorder integration", Channel.ChannelType.STANDARD);
            channel.setDecodeConfiguration(phase2 ? new DecodeConfigP25Phase2() : new DecodeConfigP25Phase1());
            SourceConfigRemote configuration = new SourceConfigRemote();
            configuration.setFrequency(FREQUENCY);
            channel.setSourceConfiguration(configuration);
            RemoteP25BitstreamSource source = new RemoteP25BitstreamSource(FREQUENCY,
                phase2 ? 6_000.0 : 4_800.0, "test remote recorder source", 32);
            ProcessingChain chain = new ProcessingChain(channel, new AliasModel());
            chain.setSource(source);
            // Keep the observer after the blocked recorder in the actual broadcaster's listener order.
            chain.addModules(List.of(healthy, blocked, consumer));
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            chain.start();
            try
            {
                byte[] first = {0, 1, 2, 3};
                expected.writeBytes(first);
                assertTrue(source.offer(first, 1_000L, 0));
                assertTrue(blockedOpen.await(2, TimeUnit.SECONDS));
                for(int sequence = 1; sequence < 9; sequence++)
                {
                    byte[] bytes = {(byte)sequence, (byte)(sequence + 16), (byte)(sequence + 32)};
                    expected.writeBytes(bytes);
                    assertTrue(source.offer(bytes, 1_000L + sequence, sequence));
                }
                assertTrue(consumer.received.await(3, TimeUnit.SECONDS),
                    "The full secondary recorder queue must not stop later packet consumers");
                assertEquals(1L, releaseDisk.getCount());
                assertEquals(0L, source.getDroppedPacketCount());
                assertEquals(9, consumer.packets.size());
                ByteArrayOutputStream observed = new ByteArrayOutputStream();
                for(int sequence = 0; sequence < consumer.packets.size(); sequence++)
                {
                    assertEquals((long)sequence, consumer.packets.get(sequence).sequence());
                    observed.writeBytes(consumer.packets.get(sequence).dibits());
                }
                assertArrayEquals(expected.toByteArray(), observed.toByteArray());
                assertTrue(consumer.threads.stream().allMatch(thread -> thread.getName().equals("test remote recorder source")));
                assertNotEquals(diskThread.get(), consumer.threads.getFirst());
                source.close().get(3, TimeUnit.SECONDS);
                assertTimeoutPreemptively(Duration.ofSeconds(1), chain::stop);
                healthy.completion().get(3, TimeUnit.SECONDS);
                assertFalse(blocked.completion().isDone(), "Chain stop does not wait for blocked recording storage");
                assertEquals(1, consumer.stops.get());
                try(var files = Files.list(healthyDirectory))
                {
                    List<Path> recorded = files.toList();
                    assertEquals(1, recorded.size(), "Both listener interfaces must not duplicate remote packet recording");
                    assertTrue(recorded.getFirst().getFileName().toString().endsWith(".bits"));
                    assertArrayEquals(expected.toByteArray(), Files.readAllBytes(recorded.getFirst()));
                }
                releaseDisk.countDown();
                blocked.completion().get(3, TimeUnit.SECONDS);
            }
            finally
            {
                releaseDisk.countDown();
                chain.dispose();
                healthy.completion().get(3, TimeUnit.SECONDS);
                blocked.completion().get(3, TimeUnit.SECONDS);
            }
        }
    }

    private static final class PacketConsumer extends Module implements IP25RemoteBitstreamListener
    {
        private final List<P25RemoteBitstreamPacket> packets = new CopyOnWriteArrayList<>();
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        private final AtomicInteger stops = new AtomicInteger();
        private final CountDownLatch received;
        private final Listener<P25RemoteBitstreamPacket> listener = this::receive;
        private void receive(P25RemoteBitstreamPacket packet)
        {
            packets.add(packet);
            threads.add(Thread.currentThread());
            received.countDown();
        }

        private PacketConsumer(int count) { received = new CountDownLatch(count); }
        @Override public Listener<P25RemoteBitstreamPacket> getRemoteBitstreamListener() { return listener; }
        @Override public void start() { }
        @Override public void stop() { stops.incrementAndGet(); }
        @Override public void reset() { }
    }
}
