/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.channel.quality.ControlChannelQualityMonitor;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.metadata.site.SiteReceiverContext;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNChannelMode;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.source.config.SourceConfigRemote;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class ChannelProcessingManagerQualityTest
{
    @Test
    void samplesReceiverOwnedQualityWithoutLifecycleOrModuleLocks() throws Exception
    {
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences());
        Channel channel = channel(ChannelType.STANDARD, new DecodeConfigP25Phase1());
        channel.activateProcessingIncarnation(7);
        ControlChannelQualitySnapshot snapshot = new ControlChannelQualitySnapshot(channel,
            SiteReceiverContext.capture(channel, null, 851_012_500L), 851_012_500L, 1_000L, true,
            null, null, null, null, 100.0, 1, 0, 0, 0, 0, 1_000L);
        ControlChannelQualityMonitor monitor = new ControlChannelQualityMonitor(channel, 851_012_500L, ignored -> {})
        {
            @Override public ReceiverObservation getReceiverObservation()
            {
                return new ReceiverObservation(snapshot, 1, 1);
            }
        };
        ProcessingChain chain = new ProcessingChain(channel, null)
        {
            @Override public boolean isProcessing() { return true; }
        };
        chain.addModule(monitor);
        Field mappingsField = ChannelProcessingManager.class.getDeclaredField("mProcessingChainsMap");
        mappingsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Channel,ProcessingChain> mappings = (Map<Channel,ProcessingChain>)mappingsField.get(manager);
        mappings.put(channel, chain);
        Field moduleLockField = ProcessingChain.class.getDeclaredField("mModuleLock");
        moduleLockField.setAccessible(true);
        ReentrantLock moduleLock = (ReentrantLock)moduleLockField.get(chain);
        try
        {
            moduleLock.lock();
            try
            {
                synchronized(manager)
                {
                    assertEquals(List.of(monitor), CompletableFuture.supplyAsync(
                        manager::getControlChannelQualityMonitors).get(1, TimeUnit.SECONDS));
                }
            }
            finally { moduleLock.unlock(); }
            chain.removeModule(monitor);
            assertTrue(manager.getControlChannelQualityMonitors().isEmpty());
            chain.addModule(monitor);
            channel.deactivateProcessingIncarnation(7);
            assertTrue(manager.getControlChannelQualityMonitors().isEmpty(),
                "A removed receiver incarnation must not supply control health");

            chain.removeModule(monitor);
            DecodeConfigNXDN nxdn = new DecodeConfigNXDN();
            channel.setDecodeConfiguration(nxdn);
            channel.activateProcessingIncarnation(8);
            ControlChannelQualityMonitor nxdnMonitor =
                new ControlChannelQualityMonitor(channel, 452_012_500L, null);
            chain.addModule(nxdnMonitor);
            Field publishTime = ControlChannelQualityMonitor.class.getDeclaredField("mLastPublish");
            publishTime.setAccessible(true);
            nxdnMonitor.start();
            publishTime.setLong(nxdnMonitor, 0);
            nxdnMonitor.getHeartbeatListener().receive(null);
            assertEquals(List.of(nxdnMonitor), manager.getControlChannelQualityMonitors(),
                "The configured NXDN control receiver supplies its own observation before UI delivery");

            nxdnMonitor.stop();
            nxdn.setChannelMode(NXDNChannelMode.CONVENTIONAL);
            nxdnMonitor.start();
            publishTime.setLong(nxdnMonitor, 0);
            nxdnMonitor.getHeartbeatListener().receive(null);
            assertTrue(manager.getControlChannelQualityMonitors().isEmpty(),
                "Conventional NXDN observations cannot inherit a prior control receiver's eligibility");
            nxdnMonitor.stop();
        }
        finally
        {
            mappings.clear();
            chain.dispose();
            manager.close();
        }
    }

    @Test
    void enablesQualityOnlyForSupportedStandardParentChannels()
    {
        assertTrue(ChannelProcessingManager.supportsControlChannelQuality(
            channel(ChannelType.STANDARD, new DecodeConfigP25Phase1())));
        assertTrue(ChannelProcessingManager.supportsControlChannelQuality(
            channel(ChannelType.STANDARD, new DecodeConfigP25Phase2())));
        Channel remoteP25 = channel(ChannelType.STANDARD, new DecodeConfigP25Phase1());
        remoteP25.setSourceConfiguration(new SourceConfigRemote());
        assertTrue(ChannelProcessingManager.supportsControlChannelQuality(remoteP25),
            "remote P25 bitstreams still publish decoded control-channel quality");
        assertTrue(ChannelProcessingManager.supportsControlChannelQuality(
            channel(ChannelType.STANDARD, new DecodeConfigDMR())));
        assertTrue(ChannelProcessingManager.supportsControlChannelQuality(
            channel(ChannelType.STANDARD, new DecodeConfigNXDN())));

        assertFalse(ChannelProcessingManager.supportsControlChannelQuality(
            channel(ChannelType.TRAFFIC, new DecodeConfigDMR())));
        assertFalse(ChannelProcessingManager.supportsControlChannelQuality(
            channel(ChannelType.STANDARD, new DecodeConfigNBFM())));
        assertFalse(ChannelProcessingManager.supportsControlChannelQuality(null));
    }

    @Test
    void usesConfiguredInternalTimeslotNumbersForTrafficPreload()
    {
        assertArrayEquals(new int[]{1, 2}, ChannelProcessingManager.getTrafficIdentifierTimeslots(
            channel(ChannelType.TRAFFIC, new DecodeConfigDMR())));
        assertArrayEquals(new int[]{1, 2}, ChannelProcessingManager.getTrafficIdentifierTimeslots(
            channel(ChannelType.TRAFFIC, new DecodeConfigP25Phase2())));
        assertArrayEquals(new int[]{0}, ChannelProcessingManager.getTrafficIdentifierTimeslots(
            channel(ChannelType.TRAFFIC, new DecodeConfigP25Phase1())));
        assertArrayEquals(new int[]{0}, ChannelProcessingManager.getTrafficIdentifierTimeslots(
            channel(ChannelType.TRAFFIC, new DecodeConfigNXDN())));
    }

    private static Channel channel(ChannelType type, DecodeConfiguration configuration)
    {
        Channel channel = new Channel("Test", type);
        channel.setDecodeConfiguration(configuration);
        return channel;
    }
}
