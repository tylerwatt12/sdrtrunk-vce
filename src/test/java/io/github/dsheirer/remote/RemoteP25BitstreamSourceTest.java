/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.quality.ControlChannelQualityMonitor;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.config.SourceConfigRemote;
import io.github.dsheirer.source.heartbeat.Heartbeat;
import io.github.dsheirer.util.concurrent.BoundedMpscReferenceQueue;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteP25BitstreamSourceTest
{
    @Test
    void publishesRemoteDecodeQualityWhileTheBitstreamIsIdle() throws Exception
    {
        Channel channel = new Channel("Remote control", Channel.ChannelType.STANDARD);
        channel.setConfigurationId("11111111-1111-4111-8111-111111111111");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        SourceConfigRemote configuration = new SourceConfigRemote();
        configuration.setFrequency(851_012_500L);
        channel.setSourceConfiguration(configuration);
        List<ControlChannelQualitySnapshot> snapshots = new CopyOnWriteArrayList<>();
        List<String> publicationThreads = new CopyOnWriteArrayList<>();
        CountDownLatch published = new CountDownLatch(1);
        ControlChannelQualityMonitor monitor = new ControlChannelQualityMonitor(channel, 851_012_500L, snapshot ->
        {
            if(snapshot.active() && snapshot.validFrames() > 0)
            {
                snapshots.add(snapshot);
                publicationThreads.add(Thread.currentThread().getName());
                published.countDown();
            }
        });
        RemoteP25BitstreamSource source =
            new RemoteP25BitstreamSource(851_012_500L, 6_000.0, "test remote decoder", 1);
        source.addHeartbeatListener(monitor.getHeartbeatListener());
        source.setRemoteBitstreamListener(packet ->
        {
            CorrectedBinaryMessage bits = new CorrectedBinaryMessage(320);
            MacMessage frame = new MacMessage(0, DataUnitID.UNSCRAMBLED_LCCH, bits,
                packet.captureTimestamp(), null);
            frame.setValid(true);
            monitor.getMessageListener().receive(frame);
        });

        try
        {
            monitor.start();
            assertTrue(source.offer(new byte[]{0x01}, System.currentTimeMillis(), 1L));
            source.start();
            assertTrue(published.await(3, TimeUnit.SECONDS), "source heartbeats publish retained decode measurements");
            ControlChannelQualitySnapshot quality = snapshots.getFirst();
            assertEquals(100.0, quality.decodeHealthPercent());
            assertEquals(1L, quality.validFrames());
            assertEquals(channel.getConfigurationId(), quality.configurationId());
            assertNull(quality.signalDbfs(), "remote bits do not contain RF signal measurements");
            assertNull(quality.averageSignalDbfs());
            assertTrue(publicationThreads.getFirst().startsWith("sdrtrunk-control-quality-"));
        }
        finally
        {
            source.stop();
            monitor.stop();
        }
    }

    @Test
    void contendedHeartbeatObserverAndFullHandoffNeverDelayRemotePackets() throws Exception
    {
        RemoteP25BitstreamSource source =
            new RemoteP25BitstreamSource(851_012_500L, 4_800.0, "test isolated remote decoder", 2);
        ReentrantLock diagnosticLock = new ReentrantLock();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(2);
        CountDownLatch secondListener = new CountDownLatch(1);
        List<String> packetThreads = new CopyOnWriteArrayList<>();
        List<String> heartbeatThreads = new CopyOnWriteArrayList<>();
        source.setRemoteBitstreamListener(packet ->
        {
            packetThreads.add(Thread.currentThread().getName());
            received.countDown();
        });
        source.addHeartbeatListener(heartbeat ->
        {
            heartbeatThreads.add(Thread.currentThread().getName());
            blocked.countDown();
            diagnosticLock.lock();
            diagnosticLock.unlock();
        });
        source.addHeartbeatListener(heartbeat -> secondListener.countDown());
        List<RemoteP25BitstreamSource> queuedSources = new ArrayList<>();
        BoundedMpscReferenceQueue<?> heartbeatQueue = heartbeatQueue();
        diagnosticLock.lock();

        try
        {
            source.start();
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            for(int index = 0; index <= heartbeatQueue.capacity(); index++)
            {
                RemoteP25BitstreamSource queued = sourceWithHeartbeatRun();
                queuedSources.add(queued);
                offerHeartbeat(queued);
                offerHeartbeat(queued);
            }
            assertEquals(heartbeatQueue.capacity(), heartbeatQueue.size(), "handoff stays bounded and coalesces ticks");
            assertTrue(source.offer(new byte[]{0x01}, 1_000L, 1L));
            assertTrue(source.offer(new byte[]{0x02}, 1_001L, 2L));
            assertTrue(received.await(1, TimeUnit.SECONDS), "blocked observers cannot occupy the decoder worker");
            assertEquals(List.of("test isolated remote decoder", "test isolated remote decoder"), packetThreads);
            assertTrue(heartbeatThreads.getFirst().startsWith("sdrtrunk-remote-heartbeat-"));
            assertEquals(1L, secondListener.getCount());
            queuedSources.forEach(RemoteP25BitstreamSource::stop);
            diagnosticLock.unlock();
            assertTrue(secondListener.await(2, TimeUnit.SECONDS));
            awaitHeartbeatQueueEmpty();
        }
        finally
        {
            if(diagnosticLock.isHeldByCurrentThread()) diagnosticLock.unlock();
            queuedSources.forEach(RemoteP25BitstreamSource::stop);
            source.stop();
        }
    }

    @Test
    void queuedHeartbeatCannotCrossStopAndListenerRebind() throws Exception
    {
        RemoteP25BitstreamSource blocker =
            new RemoteP25BitstreamSource(851_012_500L, 4_800.0, "test blocking heartbeat", 1);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blocker.addHeartbeatListener(heartbeat ->
        {
            blocked.countDown();
            awaitUninterruptibly(release);
        });
        RemoteP25BitstreamSource rebound = sourceWithHeartbeatRun();
        AtomicInteger oldTicks = new AtomicInteger();
        AtomicInteger newTicks = new AtomicInteger();
        Listener<Heartbeat> oldListener = heartbeat -> oldTicks.incrementAndGet();
        Listener<Heartbeat> newListener = heartbeat -> newTicks.incrementAndGet();
        rebound.addHeartbeatListener(oldListener);

        try
        {
            blocker.start();
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            offerHeartbeat(rebound);
            rebound.stop();
            rebound.removeHeartbeatListener(oldListener);
            rebound.addHeartbeatListener(newListener);
            release.countDown();
            awaitHeartbeatQueueEmpty();
            assertEquals(0, oldTicks.get());
            assertEquals(0, newTicks.get(), "the old queued lifecycle cannot reach newly bound listeners");
            rebound.reset();
            rebound.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while(newTicks.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5L);
            assertTrue(newTicks.get() > 0, "a new source lifecycle resumes heartbeat delivery");
        }
        finally
        {
            release.countDown();
            blocker.stop();
            rebound.stop();
        }
    }

    @Test
    void finishingStoppedDecoderCannotCloseItsReplacementLifecycle() throws Exception
    {
        RemoteP25BitstreamSource source =
            new RemoteP25BitstreamSource(851_012_500L, 4_800.0, "test rebound remote decoder", 2);
        CountDownLatch oldDecoderEntered = new CountDownLatch(1);
        CountDownLatch releaseOldDecoder = new CountDownLatch(1);
        CountDownLatch oldDecoderFinished = new CountDownLatch(1);
        CountDownLatch replacementReceived = new CountDownLatch(2);
        source.setRemoteBitstreamListener(packet ->
        {
            oldDecoderEntered.countDown();
            awaitUninterruptibly(releaseOldDecoder);
            oldDecoderFinished.countDown();
        });

        try
        {
            assertTrue(source.offer(new byte[]{0x01}, 1_000L, 1L));
            source.start();
            assertTrue(oldDecoderEntered.await(2, TimeUnit.SECONDS));
            source.stop();
            source.reset();
            source.setRemoteBitstreamListener(packet -> replacementReceived.countDown());
            source.start();
            assertTrue(source.offer(new byte[]{0x02}, 2_000L, 1L));
            releaseOldDecoder.countDown();
            assertTrue(oldDecoderFinished.await(2, TimeUnit.SECONDS));
            assertTrue(source.offer(new byte[]{0x03}, 2_001L, 2L));
            assertTrue(replacementReceived.await(2, TimeUnit.SECONDS));
            source.close().get(2, TimeUnit.SECONDS);
        }
        finally
        {
            releaseOldDecoder.countDown();
            source.stop();
        }
    }

    @Test
    void exposesStableSourceEventListenerIdentity()
    {
        RemoteP25BitstreamSource source =
            new RemoteP25BitstreamSource(851_012_500L, 4_800.0, "test remote", 1);

        assertSame(source.getSourceEventListener(), source.getSourceEventListener());
    }

    @Test
    void offerIsNonBlockingBoundedAndDefensivelyCopies() throws Exception
    {
        RemoteP25BitstreamSource source = new RemoteP25BitstreamSource(851_012_500L, 4_800.0, "test remote", 1);
        byte[] dibits = new byte[]{0x12, 0x34};
        assertTrue(source.offer(dibits, 1_000L, 10L));
        dibits[0] = 0x55;
        assertFalse(source.offer(new byte[]{0x01}, 1_001L, 11L));
        assertEquals(1L, source.getDroppedPacketCount());
        CountDownLatch received = new CountDownLatch(1);
        List<P25RemoteBitstreamPacket> packets = new CopyOnWriteArrayList<>();
        source.setRemoteBitstreamListener(packet ->
        {
            packets.add(packet);
            received.countDown();
        });

        try
        {
            source.start();
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertArrayEquals(new byte[]{0x12, 0x34}, packets.getFirst().dibits());
            assertTrue(packets.getFirst().discontinuity());
        }
        finally
        {
            source.stop();
        }
    }

    @Test
    void sequenceGapMarksOnlyTheNextPacketDiscontinuous() throws Exception
    {
        RemoteP25BitstreamSource source =
            new RemoteP25BitstreamSource(851_012_500L, 4_800.0, "test remote", 8);
        CountDownLatch received = new CountDownLatch(3);
        List<P25RemoteBitstreamPacket> packets = new CopyOnWriteArrayList<>();
        source.setRemoteBitstreamListener(packet ->
        {
            packets.add(packet);
            received.countDown();
        });

        try
        {
            assertTrue(source.offer(new byte[]{0x01}, 1_000L, 10L));
            assertTrue(source.offer(new byte[]{0x02}, 1_001L, 12L));
            assertTrue(source.offer(new byte[]{0x03}, 1_002L, 13L));
            source.start();
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertTrue(packets.get(0).discontinuity());
            assertTrue(packets.get(1).discontinuity());
            assertFalse(packets.get(2).discontinuity());
        }
        finally
        {
            source.stop();
        }
    }

    @Test
    void closeDrainsAcceptedPacketsAndRejectsNewInput() throws Exception
    {
        RemoteP25BitstreamSource source =
            new RemoteP25BitstreamSource(851_012_500L, 4_800.0, "test remote", 2);
        CountDownLatch received = new CountDownLatch(1);
        source.setRemoteBitstreamListener(packet -> received.countDown());
        assertTrue(source.offer(new byte[]{0x01}, 1_000L, 1L));
        var drained = source.close();
        assertFalse(source.offer(new byte[]{0x02}, 1_001L, 2L));

        try
        {
            source.start();
            assertTrue(received.await(2, TimeUnit.SECONDS));
            drained.get(2, TimeUnit.SECONDS);
        }
        finally
        {
            source.stop();
        }
    }

    @Test
    void resetWhileRunningRefreshesTheCloseDrainFuture() throws Exception
    {
        RemoteP25BitstreamSource source =
            new RemoteP25BitstreamSource(851_012_500L, 4_800.0, "test reset remote decoder", 1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch decoded = new CountDownLatch(1);
        source.addHeartbeatListener(heartbeat -> workerStarted.countDown());
        source.setRemoteBitstreamListener(packet -> decoded.countDown());

        try
        {
            source.start();
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
            source.reset();
            assertTrue(source.offer(new byte[]{0x01}, 1_000L, 1L));
            CompletableFuture<Void> drained = source.close();
            assertTrue(decoded.await(2, TimeUnit.SECONDS));
            drained.get(2, TimeUnit.SECONDS);
            assertFalse(source.offer(new byte[]{0x02}, 1_001L, 2L));
        }
        finally
        {
            source.stop();
        }
    }

    @Test
    void frequencyUpdateDropsOldCarrierAndNotifiesDownstream() throws Exception
    {
        RemoteP25BitstreamSource source =
            new RemoteP25BitstreamSource(851_012_500L, 4_800.0, "test remote", 2);
        List<SourceEvent> sourceEvents = new CopyOnWriteArrayList<>();
        source.setSourceEventListener(sourceEvents::add);
        assertTrue(source.offer(new byte[]{0x01}, 1_000L, 1L));

        source.updateFrequency(852_012_500L);

        CountDownLatch received = new CountDownLatch(1);
        List<P25RemoteBitstreamPacket> packets = new CopyOnWriteArrayList<>();
        source.setRemoteBitstreamListener(packet ->
        {
            packets.add(packet);
            received.countDown();
        });
        assertTrue(source.offer(new byte[]{0x02}, 2_000L, 2L));

        try
        {
            source.start();
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(852_012_500L, source.getFrequency());
            assertEquals(1L, source.getDroppedPacketCount());
            assertEquals(1, packets.size());
            assertTrue(packets.getFirst().discontinuity());
            assertEquals(1, sourceEvents.size());
            assertEquals(SourceEvent.Event.NOTIFICATION_FREQUENCY_CHANGE, sourceEvents.getFirst().getEvent());
            assertEquals(852_012_500L, sourceEvents.getFirst().getValue().longValue());
        }
        finally
        {
            source.stop();
        }

        assertThrows(IllegalArgumentException.class, () -> source.updateFrequency(0L));
    }

    /** Exercise the real bounded handoff without hundreds of unrelated decoder threads. */
    private static RemoteP25BitstreamSource sourceWithHeartbeatRun() throws Exception
    {
        RemoteP25BitstreamSource source =
            new RemoteP25BitstreamSource(851_012_500L, 4_800.0, "test remote heartbeat", 1);
        Class<?> runType = Class.forName(RemoteP25BitstreamSource.class.getName() + "$SourceRun");
        Constructor<?> constructor = runType.getDeclaredConstructor(RemoteP25BitstreamSource.class,
            CompletableFuture.class);
        constructor.setAccessible(true);
        Object run = constructor.newInstance(source, new CompletableFuture<>());
        Field field = RemoteP25BitstreamSource.class.getDeclaredField("mRun");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Object> currentRun = (AtomicReference<Object>)field.get(source);
        currentRun.set(run);
        return source;
    }

    private static void offerHeartbeat(RemoteP25BitstreamSource source) throws Exception
    {
        Field field = RemoteP25BitstreamSource.class.getDeclaredField("mRun");
        field.setAccessible(true);
        Object run = ((AtomicReference<?>)field.get(source)).get();
        Method offer = RemoteP25BitstreamSource.class.getDeclaredMethod("offerHeartbeat", run.getClass());
        offer.setAccessible(true);
        offer.invoke(source, run);
    }

    private static BoundedMpscReferenceQueue<?> heartbeatQueue() throws Exception
    {
        Field field = RemoteP25BitstreamSource.class.getDeclaredField("HEARTBEAT_QUEUE");
        field.setAccessible(true);
        return (BoundedMpscReferenceQueue<?>)field.get(null);
    }

    private static void awaitHeartbeatQueueEmpty() throws Exception
    {
        BoundedMpscReferenceQueue<?> queue = heartbeatQueue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while(queue.size() > 0 && System.nanoTime() < deadline) Thread.sleep(5L);
        assertEquals(0, queue.size());
    }

    private static void awaitUninterruptibly(CountDownLatch latch)
    {
        boolean interrupted = false;
        while(true)
        {
            try
            {
                latch.await();
                break;
            }
            catch(InterruptedException exception)
            {
                interrupted = true;
            }
        }
        if(interrupted) Thread.currentThread().interrupt();
    }
}
