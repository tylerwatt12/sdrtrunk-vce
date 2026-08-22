/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.message.DroppedSamplesMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.UnknownDataMessage;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer2.SACCHFragment;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.SourceEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class ControlChannelQualityMonitorTest
{
    private static final String GUID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    void publishesRollingSignalAndDecodeHealthAndClearsOnRotation()
    {
        Channel channel = new Channel("Test Site", ChannelType.STANDARD);
        channel.setRadresGuid(GUID);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        List<ControlChannelQualitySnapshot> snapshots = new CopyOnWriteArrayList<>();
        ControlChannelQualityMonitor monitor =
            new ControlChannelQualityMonitor(channel, 856_137_500L, snapshots::add);
        monitor.start();
        monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -10.0));
        monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -20.0));
        monitor.getMessageListener().receive(mac(1_000L, 0, true, 2));
        monitor.getMessageListener().receive(mac(1_001L, 0, false, 4));
        monitor.getMessageListener().receive(new DroppedSamplesMessage(1_002L, 320, Protocol.APCO25_PHASE2));
        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        awaitSnapshotCount(snapshots, 1);

        ControlChannelQualitySnapshot snapshot = snapshots.getLast();
        assertTrue(snapshot.active());
        assertEquals(GUID, snapshot.guid());
        assertEquals(856_137_500L, snapshot.frequencyHz());
        assertEquals(-20.0, snapshot.signalDbfs());
        assertEquals(-12.596, snapshot.averageSignalDbfs(), 0.001);
        assertEquals(-20.0, snapshot.minimumSignalDbfs());
        assertEquals(-10.0, snapshot.maximumSignalDbfs());
        assertEquals(33.333, snapshot.decodeHealthPercent(), 0.001);
        assertEquals(1, snapshot.validFrames());
        assertEquals(1, snapshot.invalidFrames());
        assertEquals(6, snapshot.correctedBits());
        assertEquals(320, snapshot.droppedBits());
        assertEquals(1_000L, snapshot.lastValidDecodeMs());

        monitor.getSourceEventListener().receive(SourceEvent.frequencyChange(null, 855_137_500L));
        awaitSnapshotCount(snapshots, 2);
        ControlChannelQualitySnapshot inactive = snapshots.getLast();
        assertFalse(inactive.active());
        assertEquals(856_137_500L, inactive.frequencyHz());

        monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -30.0));
        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        awaitSnapshotCount(snapshots, 3);
        ControlChannelQualitySnapshot rotated = snapshots.getLast();
        assertTrue(rotated.active());
        assertEquals(855_137_500L, rotated.frequencyHz());
        assertEquals(-30.0, rotated.averageSignalDbfs());
        assertNull(rotated.decodeHealthPercent());
        assertEquals(0, rotated.validFrames());
        monitor.stop();
        assertFalse(snapshots.getLast().active());
    }

    @Test
    void countsOneDmrDataBurstWithSlotTypeAndDmrSyncLossNormalization()
    {
        DecodeConfigDMR config = new DecodeConfigDMR();
        Channel channel = channel(config);
        List<ControlChannelQualitySnapshot> snapshots = new CopyOnWriteArrayList<>();
        ControlChannelQualityMonitor monitor =
            new ControlChannelQualityMonitor(channel, 452_012_500L, snapshots::add);
        monitor.start();

        monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -15.0));
        monitor.getMessageListener().receive(dmr(1_000L, true, true, 2));
        monitor.getMessageListener().receive(dmr(1_030L, false, false, 4));
        monitor.getMessageListener().receive(new SyncLossMessage(1_060L, 288, Protocol.DMR));
        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        awaitSnapshotCount(snapshots, 1);

        ControlChannelQualitySnapshot snapshot = snapshots.getLast();
        assertEquals(1, snapshot.validFrames());
        assertEquals(1, snapshot.invalidFrames());
        assertEquals(6, snapshot.correctedBits());
        assertEquals(288, snapshot.syncLossBits());
        assertEquals(-15.0, snapshot.signalDbfs());
        assertEquals(33.333, snapshot.decodeHealthPercent(), 0.001);
        assertEquals(1_000L, snapshot.lastValidDecodeMs());
    }

    @Test
    void honorsDmrIgnoreCrcButStillRequiresAValidSlotType()
    {
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setIgnoreCRCChecksums(true);
        Channel channel = channel(config);
        List<ControlChannelQualitySnapshot> snapshots = new CopyOnWriteArrayList<>();
        ControlChannelQualityMonitor monitor =
            new ControlChannelQualityMonitor(channel, 452_012_500L, snapshots::add);
        monitor.start();

        UnknownDataMessage ignoredCrc = dmr(2_000L, false, true, 0);
        monitor.getMessageListener().receive(ignoredCrc);
        monitor.getMessageListener().receive(dmr(2_030L, false, false, 0, false));
        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        awaitSnapshotCount(snapshots, 1);

        ControlChannelQualitySnapshot snapshot = snapshots.getLast();
        assertEquals(1, snapshot.validFrames());
        assertEquals(1, snapshot.invalidFrames());
        assertEquals(50.0, snapshot.decodeHealthPercent());
    }

    @Test
    void countsOneNxdnRfFrameCarrierForRcchAndTypeDAndNormalizesSyncLoss()
    {
        Channel channel = channel(new DecodeConfigNXDN());
        List<ControlChannelQualitySnapshot> snapshots = new CopyOnWriteArrayList<>();
        ControlChannelQualityMonitor monitor =
            new ControlChannelQualityMonitor(channel, 451_012_500L, snapshots::add);
        monitor.start();

        monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -30.0));
        SACCHFragment rcch = nxdn(3_000L, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL, true, 3);
        SACCHFragment sameRcchFrame = nxdn(3_000L, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL, true, 7);
        SACCHFragment typeD = nxdn(3_040L, LICH.RTCH_2_OUTBOUND_SINGLE_FACCH1_FACCH1, false, 5);
        rcch.setRfFrameQuality(true, 3);
        typeD.setRfFrameQuality(false, 5);

        monitor.getMessageListener().receive(rcch);
        monitor.getMessageListener().receive(sameRcchFrame);
        monitor.getMessageListener().receive(typeD);
        monitor.getMessageListener().receive(new SyncLossMessage(3_080L, 384, Protocol.NXDN));
        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        awaitSnapshotCount(snapshots, 1);

        ControlChannelQualitySnapshot snapshot = snapshots.getLast();
        assertEquals(1, snapshot.validFrames());
        assertEquals(1, snapshot.invalidFrames());
        assertEquals(8, snapshot.correctedBits());
        assertEquals(384, snapshot.syncLossBits());
        assertEquals(-30.0, snapshot.signalDbfs());
        assertEquals(33.333, snapshot.decodeHealthPercent(), 0.001);
        assertEquals(3_000L, snapshot.lastValidDecodeMs());
    }

    @Test
    void clearsDmrWindowOnFrequencyRotation()
    {
        Channel channel = channel(new DecodeConfigDMR());
        List<ControlChannelQualitySnapshot> snapshots = new CopyOnWriteArrayList<>();
        ControlChannelQualityMonitor monitor =
            new ControlChannelQualityMonitor(channel, 452_012_500L, snapshots::add);
        monitor.start();
        monitor.getMessageListener().receive(dmr(4_000L, true, false, 0));
        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        awaitSnapshotCount(snapshots, 1);

        monitor.getSourceEventListener().receive(
            SourceEvent.frequencyRotationSuccessNotification(null, 453_012_500L));
        awaitSnapshotCount(snapshots, 2);
        assertFalse(snapshots.getLast().active());
        assertEquals(452_012_500L, snapshots.getLast().frequencyHz());

        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        awaitSnapshotCount(snapshots, 3);
        ControlChannelQualitySnapshot rotated = snapshots.getLast();
        assertEquals(453_012_500L, rotated.frequencyHz());
        assertEquals(0, rotated.validFrames());
        assertNull(rotated.decodeHealthPercent());
    }

    @Test
    void decoderAndSourceCallbacksDropImmediatelyWhenStateIsContended() throws Exception
    {
        Channel channel = channel(new DecodeConfigDMR());
        List<ControlChannelQualitySnapshot> snapshots = new CopyOnWriteArrayList<>();
        ControlChannelQualityMonitor monitor =
            new ControlChannelQualityMonitor(channel, 452_012_500L, snapshots::add);
        monitor.start();
        ReentrantLock stateLock = stateLock(monitor);
        CountDownLatch callbacksReturned = new CountDownLatch(2);
        Thread decoderCallback = new Thread(() ->
        {
            monitor.getMessageListener().receive(dmr(5_000L, true, false, 0));
            callbacksReturned.countDown();
        }, "test-quality-contended-decoder");
        Thread sourceCallback = new Thread(() ->
        {
            monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -12.0));
            callbacksReturned.countDown();
        }, "test-quality-contended-source");

        stateLock.lock();

        try
        {
            decoderCallback.start();
            sourceCallback.start();
            assertTrue(callbacksReturned.await(1, TimeUnit.SECONDS),
                "real-time callbacks waited for contended quality state");
        }
        finally
        {
            stateLock.unlock();
            decoderCallback.join(TimeUnit.SECONDS.toMillis(5));
            sourceCallback.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertFalse(decoderCallback.isAlive());
        assertFalse(sourceCallback.isAlive());
        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        awaitSnapshotCount(snapshots, 1);
        ControlChannelQualitySnapshot snapshot = snapshots.getLast();
        assertEquals(0, snapshot.validFrames());
        assertNull(snapshot.signalDbfs());
    }

    @Test
    void callbacksReturnWhileStopConsumerIsBlocked() throws Exception
    {
        Channel channel = channel(new DecodeConfigDMR());
        List<ControlChannelQualitySnapshot> snapshots = new CopyOnWriteArrayList<>();
        CountDownLatch consumerEntered = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        CountDownLatch callbacksReturned = new CountDownLatch(2);
        ControlChannelQualityMonitor monitor = new ControlChannelQualityMonitor(channel, 452_012_500L, snapshot ->
        {
            snapshots.add(snapshot);

            if(!snapshot.active())
            {
                consumerEntered.countDown();

                try
                {
                    releaseConsumer.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        });
        monitor.start();
        monitor.getMessageListener().receive(dmr(6_000L, true, false, 0));
        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        awaitSnapshotCount(snapshots, 1);
        Thread stopThread = new Thread(monitor::stop, "test-quality-blocked-stop-consumer");
        Thread decoderCallback = new Thread(() ->
        {
            monitor.getMessageListener().receive(dmr(6_030L, true, false, 0));
            callbacksReturned.countDown();
        }, "test-quality-stop-decoder");
        Thread sourceCallback = new Thread(() ->
        {
            monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -18.0));
            callbacksReturned.countDown();
        }, "test-quality-stop-source");

        try
        {
            stopThread.start();
            assertTrue(consumerEntered.await(5, TimeUnit.SECONDS), "stop did not publish its inactive snapshot");
            decoderCallback.start();
            sourceCallback.start();
            assertTrue(callbacksReturned.await(1, TimeUnit.SECONDS),
                "real-time callbacks waited for the blocked stop consumer");
        }
        finally
        {
            releaseConsumer.countDown();
            stopThread.join(TimeUnit.SECONDS.toMillis(5));
            decoderCallback.join(TimeUnit.SECONDS.toMillis(5));
            sourceCallback.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertFalse(stopThread.isAlive());
        assertFalse(decoderCallback.isAlive());
        assertFalse(sourceCallback.isAlive());
        assertEquals(2, snapshots.size());
        assertFalse(snapshots.getLast().active());
        assertEquals(1, snapshots.getLast().validFrames());
    }

    @Test
    void delayedActivePublicationCannotFollowStopInactive() throws Exception
    {
        Channel channel = channel(new DecodeConfigDMR());
        List<ControlChannelQualitySnapshot> snapshots = new CopyOnWriteArrayList<>();
        CountDownLatch activeSnapshotCreated = new CountDownLatch(1);
        CountDownLatch releaseActivePublication = new CountDownLatch(1);
        ControlChannelQualityMonitor monitor =
            new ControlChannelQualityMonitor(channel, 452_012_500L, snapshots::add);
        monitor.start();
        monitor.getMessageListener().receive(dmr(7_000L, true, false, 0));
        monitor.setBeforeAsyncPublicationForTest(() ->
        {
            activeSnapshotCreated.countDown();

            try
            {
                releaseActivePublication.await();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        });
        Thread heartbeatThread = new Thread(() -> monitor.publishIfDue(
            System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS),
            "test-quality-delayed-active-publication");

        try
        {
            heartbeatThread.start();
            assertTrue(activeSnapshotCreated.await(5, TimeUnit.SECONDS),
                "heartbeat did not reach the pre-publication barrier");
            monitor.stop();
            assertEquals(1, snapshots.size());
            assertFalse(snapshots.getFirst().active());
            assertEquals(1, snapshots.getFirst().validFrames());
        }
        finally
        {
            monitor.setBeforeAsyncPublicationForTest(null);
            releaseActivePublication.countDown();
            heartbeatThread.join(TimeUnit.SECONDS.toMillis(5));
            awaitPublicationIdle(monitor);
        }

        assertFalse(heartbeatThread.isAlive());
        assertEquals(1, snapshots.size(), "delayed active snapshot published after the stop snapshot");
        assertFalse(snapshots.getFirst().active());
    }

    @Test
    void realtimePublicationsRunOnlyOnDedicatedQualityWorkers() throws Exception
    {
        Channel channel = channel(new DecodeConfigDMR());
        List<ControlChannelQualitySnapshot> snapshots = new CopyOnWriteArrayList<>();
        List<String> consumerThreads = new CopyOnWriteArrayList<>();
        ControlChannelQualityMonitor monitor = new ControlChannelQualityMonitor(channel, 452_012_500L, snapshot ->
        {
            snapshots.add(snapshot);
            consumerThreads.add(Thread.currentThread().getName());
        });
        monitor.start();
        Thread heartbeatProducer = new Thread(() -> monitor.publishIfDue(
            System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS),
            "test-quality-heartbeat-producer");
        Thread sourceProducer = new Thread(() -> monitor.getSourceEventListener().receive(
            SourceEvent.frequencyChange(null, 453_012_500L)), "test-quality-source-producer");

        try
        {
            heartbeatProducer.start();
            heartbeatProducer.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(heartbeatProducer.isAlive());
            awaitSnapshotCount(snapshots, 1);
            awaitPublicationIdle(monitor);

            sourceProducer.start();
            sourceProducer.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(sourceProducer.isAlive());
            awaitSnapshotCount(snapshots, 2);
            awaitPublicationIdle(monitor);

            assertEquals(2, consumerThreads.size());

            for(String threadName: consumerThreads)
            {
                assertTrue(threadName.startsWith("sdrtrunk-control-quality-"));
                assertNotEquals(heartbeatProducer.getName(), threadName);
                assertNotEquals(sourceProducer.getName(), threadName);
            }
        }
        finally
        {
            monitor.stop();
        }
    }

    @Test
    void blockedConsumerCoalescesLatestWithoutBlockingRealtimeProducer() throws Exception
    {
        Channel channel = channel(new DecodeConfigDMR());
        List<ControlChannelQualitySnapshot> snapshots = new CopyOnWriteArrayList<>();
        CountDownLatch firstConsumerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstConsumer = new CountDownLatch(1);
        CountDownLatch producerReturned = new CountDownLatch(1);
        ControlChannelQualityMonitor monitor = new ControlChannelQualityMonitor(channel, 452_012_500L, snapshot ->
        {
            snapshots.add(snapshot);

            if(snapshot.active() && snapshots.size() == 1)
            {
                firstConsumerEntered.countDown();

                try
                {
                    releaseFirstConsumer.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        });
        monitor.start();
        long firstPublish = System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS;
        monitor.publishIfDue(firstPublish);
        assertTrue(firstConsumerEntered.await(5, TimeUnit.SECONDS), "quality consumer did not block");
        Thread producer = new Thread(() ->
        {
            for(int index = 0; index < 100; index++)
            {
                monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -20.0 - index));
                monitor.getMessageListener().receive(dmr(8_000L + index, true, false, 0));
                monitor.publishIfDue(firstPublish + (index + 1L) *
                    ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
            }

            producerReturned.countDown();
        }, "test-quality-saturated-producer");

        try
        {
            producer.start();
            assertTrue(producerReturned.await(1, TimeUnit.SECONDS),
                "realtime producer waited for the blocked quality consumer");
            assertEquals(1, snapshots.size());
        }
        finally
        {
            releaseFirstConsumer.countDown();
            producer.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertFalse(producer.isAlive());
        awaitSnapshotCount(snapshots, 2);
        awaitPublicationIdle(monitor);
        assertEquals(2, snapshots.size(), "blocked quality observer did not coalesce to one latest snapshot");
        assertTrue(snapshots.getLast().active());
        assertEquals(-119.0, snapshots.getLast().signalDbfs());
        assertEquals(8_099L, snapshots.getLast().lastValidDecodeMs());
        monitor.stop();
    }

    @Test
    void restartWaitsForTerminalStopPublication() throws Exception
    {
        Channel channel = channel(new DecodeConfigDMR());
        List<ControlChannelQualitySnapshot> snapshots = new CopyOnWriteArrayList<>();
        CountDownLatch inactiveConsumerEntered = new CountDownLatch(1);
        CountDownLatch releaseInactiveConsumer = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);
        CountDownLatch restartAttempted = new CountDownLatch(1);
        CountDownLatch restartReturned = new CountDownLatch(1);
        AtomicBoolean blockFirstInactive = new AtomicBoolean(true);
        ControlChannelQualityMonitor monitor = new ControlChannelQualityMonitor(channel, 452_012_500L, snapshot ->
        {
            snapshots.add(snapshot);

            if(!snapshot.active() && blockFirstInactive.compareAndSet(true, false))
            {
                inactiveConsumerEntered.countDown();

                try
                {
                    releaseInactiveConsumer.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        });
        monitor.start();
        Thread stopThread = new Thread(() ->
        {
            monitor.stop();
            stopReturned.countDown();
        }, "test-quality-terminal-stop");
        Thread restartThread = new Thread(() ->
        {
            restartAttempted.countDown();
            monitor.start();
            restartReturned.countDown();
        }, "test-quality-terminal-restart");

        try
        {
            stopThread.start();
            assertTrue(inactiveConsumerEntered.await(5, TimeUnit.SECONDS),
                "stop did not enter terminal inactive publication");
            restartThread.start();
            assertTrue(restartAttempted.await(5, TimeUnit.SECONDS));
            assertFalse(stopReturned.await(200, TimeUnit.MILLISECONDS),
                "stop returned before terminal inactive publication completed");
            assertFalse(restartReturned.await(200, TimeUnit.MILLISECONDS),
                "restart overtook terminal inactive publication");
        }
        finally
        {
            releaseInactiveConsumer.countDown();
            stopThread.join(TimeUnit.SECONDS.toMillis(5));
            restartThread.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertFalse(stopThread.isAlive());
        assertFalse(restartThread.isAlive());
        assertEquals(0, stopReturned.getCount());
        assertEquals(0, restartReturned.getCount());
        assertEquals(1, snapshots.size());
        assertFalse(snapshots.getFirst().active());

        monitor.publishIfDue(System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        awaitSnapshotCount(snapshots, 2);
        assertTrue(snapshots.getLast().active(), "restarted generation did not publish after terminal inactive");
        monitor.stop();
    }

    @Test
    void preservesFrequencyClosuresWhileCoalescingLatestActive() throws Exception
    {
        Channel channel = channel(new DecodeConfigDMR());
        List<ControlChannelQualitySnapshot> snapshots = new CopyOnWriteArrayList<>();
        CountDownLatch firstClosureReady = new CountDownLatch(1);
        CountDownLatch releaseFirstClosure = new CountDownLatch(1);
        ControlChannelQualityMonitor monitor =
            new ControlChannelQualityMonitor(channel, 452_012_500L, snapshots::add);
        monitor.start();
        long firstPublish = System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS;
        monitor.publishIfDue(firstPublish);
        awaitSnapshotCount(snapshots, 1);
        awaitPublicationIdle(monitor);
        monitor.setBeforeAsyncPublicationForTest(() ->
        {
            firstClosureReady.countDown();

            try
            {
                releaseFirstClosure.await();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        });

        try
        {
            monitor.getSourceEventListener().receive(SourceEvent.frequencyChange(null, 453_012_500L));
            assertTrue(firstClosureReady.await(5, TimeUnit.SECONDS),
                "old-frequency inactive publication did not reach the worker");
            monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -20.0));
            monitor.publishIfDue(firstPublish + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
            monitor.getSourceEventListener().receive(SourceEvent.frequencyChange(null, 454_012_500L));
            monitor.getSourceEventListener().receive(SourceEvent.channelPowerLevel(null, -30.0));
            monitor.publishIfDue(firstPublish + 2 * ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        }
        finally
        {
            monitor.setBeforeAsyncPublicationForTest(null);
            releaseFirstClosure.countDown();
        }

        awaitSnapshotCount(snapshots, 4);
        awaitPublicationIdle(monitor);
        assertTrue(snapshots.get(0).active());
        assertEquals(452_012_500L, snapshots.get(0).frequencyHz());
        assertFalse(snapshots.get(1).active());
        assertEquals(452_012_500L, snapshots.get(1).frequencyHz());
        assertFalse(snapshots.get(2).active());
        assertEquals(453_012_500L, snapshots.get(2).frequencyHz());
        assertTrue(snapshots.get(3).active());
        assertEquals(454_012_500L, snapshots.get(3).frequencyHz());
        assertEquals(-30.0, snapshots.get(3).signalDbfs());
        monitor.stop();
    }

    @Test
    void listenerExceptionDoesNotKillPublicationDrain() throws Exception
    {
        Channel channel = channel(new DecodeConfigDMR());
        List<ControlChannelQualitySnapshot> accepted = new CopyOnWriteArrayList<>();
        CountDownLatch rejectedAttempt = new CountDownLatch(1);
        CountDownLatch recoveredAttempt = new CountDownLatch(1);
        AtomicBoolean rejectNext = new AtomicBoolean(true);
        ControlChannelQualityMonitor monitor = new ControlChannelQualityMonitor(channel, 452_012_500L, snapshot ->
        {
            if(rejectNext.compareAndSet(true, false))
            {
                rejectedAttempt.countDown();
                throw new IllegalStateException("Injected quality listener failure");
            }

            accepted.add(snapshot);
            recoveredAttempt.countDown();
        });
        monitor.start();
        long firstPublish = System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS;
        monitor.publishIfDue(firstPublish);
        assertTrue(rejectedAttempt.await(5, TimeUnit.SECONDS), "failing quality listener was not invoked");
        awaitPublicationIdle(monitor);

        monitor.publishIfDue(firstPublish + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        assertTrue(recoveredAttempt.await(5, TimeUnit.SECONDS), "quality worker did not survive listener failure");
        awaitPublicationIdle(monitor);
        assertEquals(1, accepted.size());
        assertTrue(accepted.getFirst().active());
        monitor.stop();
    }

    @Test
    void boundedWorkerRejectsWithoutCallerRunsAndRecovers() throws Exception
    {
        int queueCapacity = ControlChannelQualityMonitor.getPublicationQueueCapacityForTest();
        int blockerCount = queueCapacity + 1;
        CountDownLatch workersBlocked = new CountDownLatch(1);
        CountDownLatch releaseBlockers = new CountDownLatch(1);
        CountDownLatch allBlockersPublished = new CountDownLatch(blockerCount);
        List<ControlChannelQualityMonitor> blockers = new ArrayList<>();

        for(int index = 0; index < blockerCount; index++)
        {
            ControlChannelQualityMonitor blocker = new ControlChannelQualityMonitor(channel(new DecodeConfigDMR()),
                452_012_500L + index, snapshot ->
                {
                    workersBlocked.countDown();
                    allBlockersPublished.countDown();

                    try
                    {
                        releaseBlockers.await();
                    }
                    catch(InterruptedException exception)
                    {
                        Thread.currentThread().interrupt();
                    }
                });
            blocker.start();
            blockers.add(blocker);
        }

        CountDownLatch targetPublished = new CountDownLatch(1);
        List<String> targetConsumerThreads = new CopyOnWriteArrayList<>();
        ControlChannelQualityMonitor target = new ControlChannelQualityMonitor(channel(new DecodeConfigDMR()),
            460_012_500L, snapshot ->
            {
                targetConsumerThreads.add(Thread.currentThread().getName());
                targetPublished.countDown();
        });
        target.start();
        long publishAt = System.currentTimeMillis() + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS;
        CountDownLatch rejectedProducerReturned = new CountDownLatch(1);
        Thread rejectedProducer = new Thread(() ->
        {
            target.publishIfDue(publishAt);
            rejectedProducerReturned.countDown();
        }, "test-quality-rejected-producer");

        try
        {
            blockers.get(0).publishIfDue(publishAt);
            assertTrue(workersBlocked.await(5, TimeUnit.SECONDS), "quality workers did not block");

            for(int index = 1; index < blockerCount; index++)
            {
                blockers.get(index).publishIfDue(publishAt);
            }

            rejectedProducer.start();
            assertTrue(rejectedProducerReturned.await(1, TimeUnit.SECONDS),
                "bounded quality queue rejection blocked the producer");
            assertEquals(1, targetPublished.getCount(), "rejected task ran on the producer thread");
            assertTrue(targetConsumerThreads.isEmpty());
        }
        finally
        {
            releaseBlockers.countDown();
            rejectedProducer.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertFalse(rejectedProducer.isAlive());
        assertTrue(allBlockersPublished.await(10, TimeUnit.SECONDS), "bounded quality queue did not drain");
        awaitPublicationWorkerIdle();
        target.publishIfDue(publishAt + ControlChannelQualityMonitor.PUBLISH_INTERVAL_MILLISECONDS);
        assertTrue(targetPublished.await(5, TimeUnit.SECONDS), "quality publication did not recover after rejection");
        assertEquals(1, targetConsumerThreads.size());
        assertTrue(targetConsumerThreads.getFirst().startsWith("sdrtrunk-control-quality-"));
        assertNotEquals(rejectedProducer.getName(), targetConsumerThreads.getFirst());
        target.stop();

        for(ControlChannelQualityMonitor blocker: blockers)
        {
            blocker.stop();
        }
    }

    private static void awaitSnapshotCount(List<ControlChannelQualitySnapshot> snapshots, int count)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while(snapshots.size() < count && System.nanoTime() < deadline)
        {
            Thread.onSpinWait();
        }

        assertTrue(snapshots.size() >= count, "quality snapshot was not published");
    }

    private static void awaitPublicationIdle(ControlChannelQualityMonitor monitor)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while(!monitor.isPublicationIdleForTest() && System.nanoTime() < deadline)
        {
            Thread.onSpinWait();
        }

        assertTrue(monitor.isPublicationIdleForTest(), "quality publication worker did not become idle");
    }

    private static void awaitPublicationWorkerIdle()
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while(!ControlChannelQualityMonitor.isPublicationWorkerIdleForTest() && System.nanoTime() < deadline)
        {
            Thread.onSpinWait();
        }

        assertTrue(ControlChannelQualityMonitor.isPublicationWorkerIdleForTest(),
            "quality publication worker did not recover from saturation");
    }

    private static ReentrantLock stateLock(ControlChannelQualityMonitor monitor)
    {
        try
        {
            Field field = ControlChannelQualityMonitor.class.getDeclaredField("mStateLock");
            field.setAccessible(true);
            return (ReentrantLock)field.get(monitor);
        }
        catch(ReflectiveOperationException exception)
        {
            throw new AssertionError("Unable to inspect quality-monitor state lock", exception);
        }
    }

    private static MacMessage mac(long timestamp, int timeslot, boolean valid, int correctedBits)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(320);
        bits.setCorrectedBitCount(correctedBits);
        MacMessage message = new MacMessage(timeslot, DataUnitID.UNSCRAMBLED_LCCH, bits, timestamp, null);
        message.setValid(valid);
        return message;
    }

    private static Channel channel(io.github.dsheirer.module.decode.config.DecodeConfiguration config)
    {
        Channel channel = new Channel("Test Site", ChannelType.STANDARD);
        channel.setRadresGuid(GUID);
        channel.setDecodeConfiguration(config);
        return channel;
    }

    private static UnknownDataMessage dmr(long timestamp, boolean valid, boolean ras, int correctedBits)
    {
        return dmr(timestamp, valid, ras, correctedBits, true);
    }

    private static UnknownDataMessage dmr(long timestamp, boolean valid, boolean ras, int correctedBits,
                                          boolean validSlotType)
    {
        CorrectedBinaryMessage slotBits = new CorrectedBinaryMessage(288);
        SlotType slotType;

        if(validSlotType)
        {
            slotType = SlotType.getSlotType(slotBits);
        }
        else
        {
            slotType = new SlotType(new CorrectedBinaryMessage(24))
            {
                @Override
                public boolean isValid()
                {
                    return false;
                }
            };
        }

        CorrectedBinaryMessage payload = new CorrectedBinaryMessage(99);
        payload.setCorrectedBitCount(correctedBits);

        if(ras)
        {
            payload.set(96);
        }

        UnknownDataMessage message = new UnknownDataMessage(DMRSyncPattern.BASE_STATION_DATA, payload, null,
            slotType, timestamp, 1);
        message.setValid(valid);
        return message;
    }

    private static SACCHFragment nxdn(long timestamp, LICH lich, boolean valid, int correctedBits)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(26);
        bits.setCorrectedBitCount(correctedBits);
        SACCHFragment message = new SACCHFragment(bits, timestamp, lich);
        message.setValid(valid);
        return message;
    }
}
