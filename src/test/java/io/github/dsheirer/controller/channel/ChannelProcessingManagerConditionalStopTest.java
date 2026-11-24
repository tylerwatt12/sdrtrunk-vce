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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager.StopCurrentResult;
import io.github.dsheirer.controller.channel.ChannelProcessingManager.TunerChannelAssignment;
import io.github.dsheirer.controller.channel.event.ChannelStopProcessingRequest;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.MultiFrequencyTunerChannelSource;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.manager.DiscoveredUSBTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationContext;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChannelProcessingManagerConditionalStopTest
{
    @Test
    void usbRecoveryWaitsUntilAnOldSourceErrorsCrossThreadTeardownFinishes() throws Exception
    {
        AliasModel aliases = new AliasModel();
        UserPreferences preferences = new UserPreferences();
        AtomicInteger attempts = new AtomicInteger();
        TunerManager tuners = new TunerManager(preferences)
        {
            @Override public Source getSourceForUsbRecovery(SourceConfiguration configuration,
                ChannelSpecification specification, String threadName, DiscoveredTuner tuner,
                TunerAllocationContext context)
            {
                attempts.incrementAndGet();
                return null;
            }
        };
        DiscoveredUSBTuner usb = new DiscoveredUSBTuner(TunerClass.TEST_TUNER, 1, "1.1")
        {
            @Override public boolean isAvailable() { return true; }
            @Override public OperatorState getOperatorState() { return OperatorState.LIVE; }
            @Override public void stop() { }
        };
        tuners.getDiscoveredTunerRegistry().add(usb);
        ChannelProcessingManager manager = new ChannelProcessingManager(null, tuners, aliases, preferences);
        Channel channel = new Channel("Control");
        channel.setDecodeConfiguration(new io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM());
        TunerChannelSource source = source();
        source.setTunerIdentity(usb.getId());
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        ProcessingChain chain = new ProcessingChain(channel, aliases)
        {
            @Override public void stop()
            {
                cleanupEntered.countDown();
                try { org.junit.jupiter.api.Assertions.assertTrue(releaseCleanup.await(2, TimeUnit.SECONDS)); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
            }
        };
        chain.setSource(source);
        install(manager, channel, chain, 501L);
        Field recoveryField = ChannelProcessingManager.class.getDeclaredField("mUsbChannelRecovery");
        recoveryField.setAccessible(true);
        UsbChannelRecoverySupport recovery = (UsbChannelRecoverySupport)recoveryField.get(manager);
        recovery.channelStarted(channel, source, 501L);
        Thread teardown = new Thread(() -> manager.stopChannelRequest(new ChannelStopProcessingRequest(source)));
        try
        {
            teardown.start();
            org.junit.jupiter.api.Assertions.assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS));
            assertNull(manager.getProcessingChain(channel), "The old map is removed before blocking cleanup");
            recovery.tunerAvailable(usb);
            assertEquals(0, attempts.get(), "A missing map alone does not prove old teardown has finished");
            releaseCleanup.countDown();
            teardown.join(2_000);
            org.junit.jupiter.api.Assertions.assertFalse(teardown.isAlive());
            recovery.tunerAvailable(usb);
            assertEquals(1, attempts.get(), "The next lifecycle pass may restart once cleanup releases its fence");
        }
        finally { releaseCleanup.countDown(); teardown.join(2_000); manager.close(); tuners.stop(); }
    }

    @Test
    void nestedParentDisableDuringAutomaticTeardownPreservesRecoveryAndAnExplicitStopCancelsIt() throws Exception
    {
        AliasModel aliases = new AliasModel();
        UserPreferences preferences = new UserPreferences();
        TunerManager tuners = new TunerManager(preferences);
        DiscoveredUSBTuner usb = new DiscoveredUSBTuner(TunerClass.TEST_TUNER, 1, "1.1")
        {
            @Override public void stop() { }
        };
        tuners.getDiscoveredTunerRegistry().add(usb);
        ChannelProcessingManager manager = new ChannelProcessingManager(null, tuners, aliases, preferences);
        Channel channel = new Channel("Control");
        TunerChannelSource source = source();
        source.setTunerIdentity(usb.getId());
        ProcessingChain chain = new ProcessingChain(channel, aliases)
        {
            @Override public void stop()
            {
                manager.receive(ChannelEvent.requestDisable(channel));
            }
        };
        chain.setSource(source);
        install(manager, channel, chain, 401L);
        Field recoveryField = ChannelProcessingManager.class.getDeclaredField("mUsbChannelRecovery");
        recoveryField.setAccessible(true);
        ((UsbChannelRecoverySupport)recoveryField.get(manager)).channelStarted(channel, source, 401L);
        try
        {
            manager.stopChannelRequest(new ChannelStopProcessingRequest(source));
            assertNull(manager.getProcessingChain(channel));
            org.junit.jupiter.api.Assertions.assertTrue(manager.isUsbRecoveryPending(channel.getConfigurationId()),
                "The traffic manager's nested parent Stop must not erase the interrupted running state");
            manager.stop(channel);
            org.junit.jupiter.api.Assertions.assertFalse(manager.isUsbRecoveryPending(channel.getConfigurationId()));
        }
        finally { manager.close(); tuners.stop(); }
    }

    @Test
    void shutdownOfInnerUsbSourceClearsTheConfiguredRotatingChannelsRunningMapping() throws Exception
    {
        AliasModel aliases = new AliasModel();
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, aliases, new UserPreferences());
        Channel channel = new Channel("Rotating control");
        TunerChannelSource inner = source();
        MultiFrequencyTunerChannelSource wrapper = new MultiFrequencyTunerChannelSource(null, inner,
            List.of(155_100_000L, 155_200_000L), new ChannelSpecification(50_000, 12_500, 6_250, 7_000),
            null, "USB shutdown test", null, null);
        ProcessingChain chain = new ProcessingChain(channel, aliases);
        chain.setSource(wrapper);
        install(manager, channel, chain, 301L);
        channel.setProcessing(true);
        try
        {
            manager.stopChannelRequest(new ChannelStopProcessingRequest(inner));
            assertNull(manager.getProcessingChain(channel), "Inner USB source shutdown must remove the wrapper chain");
            org.junit.jupiter.api.Assertions.assertFalse(channel.isProcessing());
        }
        finally { manager.close(); }
    }

    @Test
    void staleIncarnationCannotStopAReplacementChain() throws Exception
    {
        AliasModel aliases = new AliasModel();
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, aliases,
            new UserPreferences());
        Channel channel = new Channel("Control");
        ProcessingChain original = new ProcessingChain(channel, aliases);
        install(manager, channel, original, 101L);
        TunerChannelAssignment stale = ChannelProcessingManager.tunerChannelAssignment(channel,
            851_012_500L, 101L);

        manager.stop(channel);
        ProcessingChain replacement = new ProcessingChain(channel, aliases);
        install(manager, channel, replacement, 102L);

        try
        {
            assertEquals(StopCurrentResult.NOT_OWNED, manager.stopIfCurrent(stale));
            assertSame(replacement, manager.getProcessingChain(channel));

            TunerChannelAssignment current = ChannelProcessingManager.tunerChannelAssignment(channel,
                851_012_500L, 102L);
            assertEquals(StopCurrentResult.CLAIMED, manager.stopIfCurrent(current));
            assertNull(manager.getProcessingChain(channel));
        }
        finally { manager.close(); }
    }

    @Test
    void claimedCleanupFailureRemainsDistinguishableFromNotOwned() throws Exception
    {
        AliasModel aliases = new AliasModel();
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, aliases,
            new UserPreferences());
        Channel channel = new Channel("Control");
        ProcessingChain failing = new ProcessingChain(channel, aliases)
        {
            @Override public void stop() { throw new IllegalStateException("cleanup failed"); }
        };
        install(manager, channel, failing, 201L);
        TunerChannelAssignment current = ChannelProcessingManager.tunerChannelAssignment(channel,
            851_012_500L, 201L);

        try
        {
            assertEquals(StopCurrentResult.CLAIMED_WITH_CLEANUP_FAILURE, manager.stopIfCurrent(current));
            assertNull(manager.getProcessingChain(channel));
            assertEquals(StopCurrentResult.NOT_OWNED, manager.stopIfCurrent(current));
        }
        finally { manager.close(); }
    }

    @SuppressWarnings("unchecked")
    private static void install(ChannelProcessingManager manager, Channel channel, ProcessingChain chain,
                                long incarnation) throws Exception
    {
        Field mapField = ChannelProcessingManager.class.getDeclaredField("mProcessingChainsMap");
        mapField.setAccessible(true);
        channel.activateProcessingIncarnation(incarnation);
        ((Map<Channel,ProcessingChain>)mapField.get(manager)).put(channel, chain);
        chain.getEventBus().register(manager);
    }

    private static TunerChannelSource source()
    {
        return new TunerChannelSource(null, new TunerChannel(155_100_000L, 12_500), "USB shutdown test", null)
        {
            @Override public void reset() { }
            @Override public void setFrequency(long frequency) { }
            @Override public void setFrequencyCorrection(long correction) { }
            @Override protected void setSampleRate(double sampleRate) { }
            @Override public void setListener(Listener<ComplexSamples> listener) { }
            @Override public double getSampleRate() { return 50_000; }
        };
    }
}
