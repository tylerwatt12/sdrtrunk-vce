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
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

        manager.process(new ProtocolSiteMetadataEvent(channel, dmr, 1_000));
        manager.process(new SiteMetadataEvent(channel, p25, 1_001));

        assertTrue(received.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(Protocol.DMR, Protocol.APCO25), protocols);
    }
}
