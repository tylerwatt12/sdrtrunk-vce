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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChannelProcessingManagerLifecycleLockTest
{
    private static final long TEST_FREQUENCY = 851_012_500L;

    @Test
    void p25StopNotificationDoesNotDeadlockWithConcurrentTrafficStart() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        ChannelProcessingManager manager = new ChannelProcessingManager(null,
            new RejectingTunerManager(preferences), aliasModel, preferences);
        Channel channelToStop = p25Channel("P25 traffic to stop", ChannelType.TRAFFIC);
        BlockingStopProcessingChain chain = new BlockingStopProcessingChain(channelToStop, aliasModel);
        injectProcessingChain(manager, channelToStop, chain);
        Channel channelToStart = p25Channel("Concurrent P25 traffic start", ChannelType.TRAFFIC);
        Semaphore trafficManagerLock = new Semaphore(1);
        CountDownLatch stopNotificationEntered = new CountDownLatch(1);
        CountDownLatch trafficStartOwnsLock = new CountDownLatch(1);
        CountDownLatch stopCompleted = new CountDownLatch(1);
        CountDownLatch startCompleted = new CountDownLatch(1);
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        AtomicReference<Throwable> startFailure = new AtomicReference<>();

        manager.addChannelEventListener(event ->
        {
            if(event.getChannel() == channelToStop &&
                event.getEvent() == ChannelEvent.Event.NOTIFICATION_PROCESSING_STOP)
            {
                stopNotificationEntered.countDown();
                boolean acquired = false;

                try
                {
                    trafficManagerLock.acquire();
                    acquired = true;
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
                finally
                {
                    if(acquired)
                    {
                        trafficManagerLock.release();
                    }
                }
            }
        });

        Thread stopThread = Thread.ofPlatform().daemon(true).name("test-p25-stop").unstarted(() ->
        {
            try
            {
                manager.stop(channelToStop);
            }
            catch(Throwable throwable)
            {
                stopFailure.set(throwable);
            }
            finally
            {
                stopCompleted.countDown();
            }
        });
        Thread startThread = Thread.ofPlatform().daemon(true).name("test-p25-traffic-start").unstarted(() ->
        {
            boolean acquired = false;

            try
            {
                trafficManagerLock.acquire();
                acquired = true;
                trafficStartOwnsLock.countDown();
                manager.start(channelToStart);
                startFailure.set(new AssertionError("Expected the test tuner manager to reject the source request"));
            }
            catch(ChannelException expected)
            {
                //Expected after the lifecycle monitor has been acquired and the tuner source request is rejected.
            }
            catch(Throwable throwable)
            {
                startFailure.set(throwable);
            }
            finally
            {
                if(acquired)
                {
                    trafficManagerLock.release();
                }

                startCompleted.countDown();
            }
        });

        boolean startFinishedWithoutCycle = false;
        boolean stopFinishedWithoutCycle = false;

        try
        {
            stopThread.start();
            assertTrue(chain.stopEntered().await(2, TimeUnit.SECONDS), "P25 processing-chain stop did not begin");
            startThread.start();
            assertTrue(trafficStartOwnsLock.await(2, TimeUnit.SECONDS), "concurrent traffic start did not own its lock");
            chain.releaseStop().countDown();
            assertTrue(stopNotificationEntered.await(2, TimeUnit.SECONDS), "P25 stop notification was not broadcast");
            startFinishedWithoutCycle = startCompleted.await(2, TimeUnit.SECONDS);
            stopFinishedWithoutCycle = stopCompleted.await(2, TimeUnit.SECONDS);
        }
        finally
        {
            //A semaphore is used instead of a ReentrantLock so a regressed lock cycle can be broken for test cleanup.
            chain.releaseStop().countDown();
            trafficManagerLock.release();
            stopThread.join(2_000);
            startThread.join(2_000);

            if(!stopThread.isAlive() && !startThread.isAlive())
            {
                manager.close();
            }
        }

        assertTrue(startFinishedWithoutCycle, "traffic start waited for the monitor held by P25 stop");
        assertTrue(stopFinishedWithoutCycle, "P25 stop waited for the traffic-manager lock held by traffic start");
        assertNull(startFailure.get());
        assertNull(stopFailure.get());
    }

    @Test
    void shutdownReleasesLifecycleMonitorBeforeStoppingP25Channel() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, aliasModel, preferences);
        Channel channel = p25Channel("P25 shutdown", ChannelType.STANDARD);
        BlockingStopProcessingChain chain = new BlockingStopProcessingChain(channel, aliasModel);
        injectProcessingChain(manager, channel, chain);
        CountDownLatch shutdownCompleted = new CountDownLatch(1);
        CountDownLatch lifecycleMonitorAcquired = new CountDownLatch(1);
        Thread shutdownThread = Thread.ofPlatform().daemon(true).name("test-p25-shutdown").unstarted(() ->
        {
            manager.shutdown();
            shutdownCompleted.countDown();
        });
        Thread monitorProbe = Thread.ofPlatform().daemon(true).name("test-p25-lifecycle-monitor").unstarted(() ->
        {
            synchronized(manager)
            {
                lifecycleMonitorAcquired.countDown();
            }
        });
        boolean acquiredDuringStop;

        try
        {
            shutdownThread.start();
            assertTrue(chain.stopEntered().await(2, TimeUnit.SECONDS), "shutdown did not begin P25 chain stop");
            monitorProbe.start();
            acquiredDuringStop = lifecycleMonitorAcquired.await(2, TimeUnit.SECONDS);
        }
        finally
        {
            chain.releaseStop().countDown();
            shutdownThread.join(2_000);
            monitorProbe.join(2_000);

            if(!shutdownThread.isAlive() && !monitorProbe.isAlive())
            {
                manager.close();
            }
        }

        assertTrue(acquiredDuringStop, "shutdown held the shared lifecycle monitor during P25 stop callbacks");
        assertTrue(shutdownCompleted.await(2, TimeUnit.SECONDS));
    }

    @Test
    void ordinaryNxdnStopNotificationHoldsNoManagerWideLifecycleLock() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, aliasModel, preferences);
        Channel channel = channel("NXDN traffic", ChannelType.TRAFFIC, new DecodeConfigNXDN());
        ProcessingChain chain = new ProcessingChain(channel, aliasModel);
        injectProcessingChain(manager, channel, chain);
        Object shutdownLock = fieldValue(manager, "mShutdownLock");
        AtomicBoolean stopObserved = new AtomicBoolean();
        AtomicBoolean managerMonitorHeld = new AtomicBoolean();
        AtomicBoolean shutdownMonitorHeld = new AtomicBoolean();

        manager.addChannelEventListener(event ->
        {
            if(event.getChannel() == channel && event.getEvent() == ChannelEvent.Event.NOTIFICATION_PROCESSING_STOP)
            {
                stopObserved.set(true);
                managerMonitorHeld.set(Thread.holdsLock(manager));
                shutdownMonitorHeld.set(Thread.holdsLock(shutdownLock));
            }
        });

        try
        {
            manager.stop(channel);
        }
        finally
        {
            manager.close();
        }

        assertTrue(stopObserved.get());
        assertFalse(managerMonitorHeld.get(), "NXDN stop notification held the shared lifecycle monitor");
        assertFalse(shutdownMonitorHeld.get(), "NXDN stop notification held the shutdown monitor");
    }

    @Test
    void onlyDmrChannelsUseDmrRestLifecycleSerialization()
    {
        assertTrue(ChannelProcessingManager.requiresDmrRestChannelLifecycleSerialization(
            channel("DMR standard", ChannelType.STANDARD, new DecodeConfigDMR())));
        assertTrue(ChannelProcessingManager.requiresDmrRestChannelLifecycleSerialization(
            channel("DMR traffic", ChannelType.TRAFFIC, new DecodeConfigDMR())));
        assertFalse(ChannelProcessingManager.requiresDmrRestChannelLifecycleSerialization(
            channel("P25 Phase 1", ChannelType.STANDARD, new DecodeConfigP25Phase1())));
        assertFalse(ChannelProcessingManager.requiresDmrRestChannelLifecycleSerialization(
            channel("P25 Phase 2", ChannelType.TRAFFIC, new DecodeConfigP25Phase2())));
        assertFalse(ChannelProcessingManager.requiresDmrRestChannelLifecycleSerialization(
            channel("NXDN", ChannelType.STANDARD, new DecodeConfigNXDN())));
        assertFalse(ChannelProcessingManager.requiresDmrRestChannelLifecycleSerialization(
            channel("Conventional", ChannelType.STANDARD, new DecodeConfigNBFM())));
        assertFalse(ChannelProcessingManager.requiresDmrRestChannelLifecycleSerialization(null));
    }

    private static Channel p25Channel(String name, ChannelType type)
    {
        return channel(name, type, new DecodeConfigP25Phase1());
    }

    private static Channel channel(String name, ChannelType type,
                                   io.github.dsheirer.module.decode.config.DecodeConfiguration decoder)
    {
        Channel channel = new Channel(name, type);
        channel.setDecodeConfiguration(decoder);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(TEST_FREQUENCY);
        channel.setSourceConfiguration(source);
        return channel;
    }

    @SuppressWarnings("unchecked")
    private static void injectProcessingChain(ChannelProcessingManager manager, Channel channel,
                                              ProcessingChain chain) throws Exception
    {
        Field mapField = ChannelProcessingManager.class.getDeclaredField("mProcessingChainsMap");
        mapField.setAccessible(true);
        ((Map<Channel,ProcessingChain>)mapField.get(manager)).put(channel, chain);
        chain.getEventBus().register(manager);
    }

    private static Object fieldValue(Object owner, String name) throws Exception
    {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static final class RejectingTunerManager extends TunerManager
    {
        private RejectingTunerManager(UserPreferences preferences)
        {
            super(preferences);
        }

        @Override
        public Source getSource(SourceConfiguration configuration, ChannelSpecification channelSpecification,
                                String threadName) throws SourceException
        {
            return null;
        }
    }

    private static final class BlockingStopProcessingChain extends ProcessingChain
    {
        private final AtomicBoolean mBlockFirstStop = new AtomicBoolean(true);
        private final CountDownLatch mStopEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseStop = new CountDownLatch(1);

        private BlockingStopProcessingChain(Channel channel, AliasModel aliasModel)
        {
            super(channel, aliasModel);
        }

        @Override
        public void stop()
        {
            if(mBlockFirstStop.compareAndSet(true, false))
            {
                mStopEntered.countDown();

                try
                {
                    mReleaseStop.await(10, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }

            super.stop();
        }

        private CountDownLatch stopEntered()
        {
            return mStopEntered;
        }

        private CountDownLatch releaseStop()
        {
            return mReleaseStop;
        }
    }
}
