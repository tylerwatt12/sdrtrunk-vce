/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.controller.channel;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.quality.ControlChannelQualityMonitor;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChannelProcessingManagerSiteMetadataTest
{
    @Test
    public void configurationReloadDoesNotCloseActivityModel() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, aliasModel,
            new UserPreferences());
        CountDownLatch populated = new CountDownLatch(1);
        manager.getChannelActivityModel().addActivityListener(event ->
        {
            if(!event.snapshot().rows().isEmpty())
            {
                populated.countDown();
            }
        });

        try
        {
            manager.shutdown();
            Channel channel = new Channel("reloaded", Channel.ChannelType.STANDARD);
            channel.setDecodeConfiguration(new DecodeConfigNBFM());
            manager.getChannelActivityModel().channelStarted(channel,
                List.of(new ChannelMetadata(aliasModel, 1)));
            assertTrue(populated.await(2, TimeUnit.SECONDS));
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    public void siteMetadataListenersDoNotBlockTheCallingThread() throws Exception
    {
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences());
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        AtomicReference<Thread> listenerThread = new AtomicReference<>();
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        ExecutorService caller = Executors.newSingleThreadExecutor();

        manager.addSiteMetadataListener(event -> {
            listenerThread.set(Thread.currentThread());
            listenerStarted.countDown();

            try
            {
                releaseListener.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        });

        try
        {
            Future<?> submitted = caller.submit(() -> {
                callerThread.set(Thread.currentThread());
                manager.process((SiteMetadataEvent)null);
            });

            assertTrue(listenerStarted.await(5, TimeUnit.SECONDS));
            submitted.get(1, TimeUnit.SECONDS);
            assertNotEquals(callerThread.get(), listenerThread.get());
        }
        finally
        {
            releaseListener.countDown();
            caller.shutdownNow();
            manager.close();
            assertTrue(manager.awaitSiteMetadataDrain(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void protocolListenerReceivesDirectDmrAndBridgedLegacyP25Events() throws Exception
    {
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences());
        CountDownLatch received = new CountDownLatch(2);
        List<Protocol> protocols = new java.util.concurrent.CopyOnWriteArrayList<>();
        manager.addProtocolSiteMetadataListener(event -> {
            protocols.add(event.snapshot().protocol());
            received.countDown();
        });
        Channel channel = new Channel("control", Channel.ChannelType.STANDARD);
        DMRNetworkConfigurationSnapshot dmr = new DMRNetworkConfigurationSnapshot("DMR", "TIER_III",
            1, 2, "Tier III Trunking", "TINY", null, "Control", 1, 1, List.of(), List.of());
        P25NetworkConfigurationSnapshot p25 = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(1, 2, 3, 4), null, List.of(), List.of(),
            List.of(), List.of(), List.of());

        try
        {
            manager.process(new ProtocolSiteMetadataEvent(channel, dmr, 1_000));
            manager.process(new SiteMetadataEvent(channel, p25, 1_001));

            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(Protocol.DMR, Protocol.APCO25), protocols);
        }
        finally
        {
            manager.close();
            assertTrue(manager.awaitSiteMetadataDrain(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void processingIncarnationRejectsOldActiveAndTerminalObservationsAfterSameChannelRestart()
    {
        Channel channel = new Channel("control", Channel.ChannelType.STANDARD);
        channel.setConfigurationId("123e4567-e89b-12d3-a456-426614174000");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(851_012_500L);
        channel.setSourceConfiguration(source);
        channel.activateProcessingIncarnation(41);
        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(1, 2, 3, 4), null, List.of(), List.of(),
            List.of(), List.of(), List.of());
        SiteMetadataEvent site = new SiteMetadataEvent(channel, snapshot, 1_000L, 851_012_500L);
        ControlChannelQualitySnapshot active = quality(channel, true, 1_000L);

        assertTrue(site.matchesCurrentChannel());
        assertTrue(active.matchesCurrentChannel());

        channel.deactivateProcessingIncarnation(41);
        ControlChannelQualitySnapshot terminal = new ControlChannelQualitySnapshot(channel,
            active.receiverContext(), active.frequencyHz(), 1_001L, false, active.signalDbfs(),
            active.averageSignalDbfs(), active.minimumSignalDbfs(), active.maximumSignalDbfs(),
            active.decodeHealthPercent(), active.validFrames(), active.invalidFrames(), active.correctedBits(),
            active.syncLossBits(), active.droppedBits(), active.lastValidDecodeMs());
        assertFalse(site.matchesCurrentChannel(), "active site metadata is closed synchronously with the map");
        assertFalse(active.matchesCurrentChannel(), "active quality is closed synchronously with the map");
        assertTrue(terminal.matchesCurrentChannel(),
            "the terminal quality closure can clear the same just-closed incarnation");

        channel.activateProcessingIncarnation(42);
        assertFalse(active.matchesCurrentChannel(), "old active quality cannot affect a restarted Channel object");
        assertFalse(terminal.matchesCurrentChannel(), "an old terminal closure cannot clear a newer incarnation");
        assertFalse(site.matchesCurrentChannel(), "old site metadata cannot affect a restarted Channel object");
        assertTrue(quality(channel, true, 1_002L).matchesCurrentChannel());
    }

    @Test
    public void qualityMonitorCapturesTheIncarnationWhenItStarts()
    {
        Channel channel = new Channel("control", Channel.ChannelType.STANDARD);
        channel.setConfigurationId("123e4567-e89b-12d3-a456-426614174000");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(851_012_500L);
        channel.setSourceConfiguration(source);
        List<ControlChannelQualitySnapshot> snapshots = new CopyOnWriteArrayList<>();
        ControlChannelQualityMonitor monitor = new ControlChannelQualityMonitor(channel, 851_012_500L,
            snapshots::add);

        channel.activateProcessingIncarnation(51);
        monitor.start();
        channel.deactivateProcessingIncarnation(51);
        monitor.stop();

        ControlChannelQualitySnapshot terminal = snapshots.getLast();
        assertEquals(51, terminal.receiverContext().processingIncarnation());
        assertTrue(terminal.matchesCurrentChannel());
    }

    private static ControlChannelQualitySnapshot quality(Channel channel, boolean active, long observedAt)
    {
        return new ControlChannelQualitySnapshot(channel, channel.getPersistedConfigurationId(), 851_012_500L,
            observedAt, active, -20.0, -21.0, -25.0, -18.0, 95.0, 100, 2, 1, 0, 0,
            observedAt - 1);
    }

    @Test
    public void saturatedSiteMetadataHandoffIsBoundedAndNeverRunsListenersOnProducer() throws Exception
    {
        int capacity = 8;
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences(),
            500, capacity);
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        manager.addSiteMetadataListener(event -> {
            callbacks.incrementAndGet();
            listenerEntered.countDown();

            try
            {
                releaseListener.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        });

        try
        {
            manager.process((SiteMetadataEvent)null);
            assertTrue(listenerEntered.await(2, TimeUnit.SECONDS));

            assertTimeout(Duration.ofSeconds(1), () -> {
                for(int x = 0; x < 1_000; x++)
                {
                    manager.process((SiteMetadataEvent)null);
                }
            });

            assertEquals(capacity, manager.getSiteMetadataIngressCapacity());
            assertTrue(manager.getSiteMetadataIngressSize() <= capacity);
            assertTrue(manager.getDroppedSiteMetadataCount() > 0);
            assertEquals(1, callbacks.get(), "a saturated producer must never run the blocked listener itself");

            manager.close();
            assertFalse(manager.awaitSiteMetadataDrain(20, TimeUnit.MILLISECONDS));
            releaseListener.countDown();
            assertTrue(manager.awaitSiteMetadataDrain(2, TimeUnit.SECONDS));
            assertTrue(callbacks.get() <= capacity + 1);
        }
        finally
        {
            releaseListener.countDown();
            manager.close();
        }
    }

    @Test
    public void closeFencesAcceptedSiteMetadataAndRejectsLateCallbacks() throws Exception
    {
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences());
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        manager.addSiteMetadataListener(event -> {
            callbacks.incrementAndGet();
            listenerEntered.countDown();

            try
            {
                releaseListener.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        });

        try
        {
            manager.process((SiteMetadataEvent)null);
            assertTrue(listenerEntered.await(2, TimeUnit.SECONDS));
            manager.close();
            assertFalse(manager.awaitSiteMetadataDrain(50, TimeUnit.MILLISECONDS),
                "the lifecycle fence must time out while an accepted callback remains blocked");

            releaseListener.countDown();
            assertTrue(manager.awaitSiteMetadataDrain(2, TimeUnit.SECONDS),
                "a repeated lifecycle fence must observe the accepted callback's completion");

            manager.process((SiteMetadataEvent)null);
            assertTrue(manager.awaitSiteMetadataDrain(2, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get(), "callbacks submitted after close must be rejected");
        }
        finally
        {
            releaseListener.countDown();
            manager.close();
        }
    }

    @Test
    public void siteMetadataDrainRequiresCloseAndPreservesInterruption() throws Exception
    {
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences());
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        manager.addSiteMetadataListener(event -> {
            listenerEntered.countDown();

            try
            {
                releaseListener.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        });

        try
        {
            assertThrows(IllegalStateException.class,
                () -> manager.awaitSiteMetadataDrain(1, TimeUnit.MILLISECONDS));
            manager.process((SiteMetadataEvent)null);
            assertTrue(listenerEntered.await(2, TimeUnit.SECONDS));
            manager.close();
            Thread.currentThread().interrupt();
            assertFalse(manager.awaitSiteMetadataDrain(2, TimeUnit.SECONDS));
            assertTrue(Thread.currentThread().isInterrupted(),
                "the lifecycle fence must restore the caller's interrupt status");
        }
        finally
        {
            Thread.interrupted();
            releaseListener.countDown();
            manager.close();
            assertTrue(manager.awaitSiteMetadataDrain(2, TimeUnit.SECONDS));
        }
    }
}
