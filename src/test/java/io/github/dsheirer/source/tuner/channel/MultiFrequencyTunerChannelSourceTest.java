/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.controller.channel.event.ChannelStopProcessingRequest;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.test.TestTuner;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MultiFrequencyTunerChannelSourceTest
{
    @Test
    void successfulRotationTransfersTheGapOwner() throws Exception
    {
        GapDiscoveredTuner firstOwner = new GapDiscoveredTuner("first owner");
        GapDiscoveredTuner secondOwner = new GapDiscoveredTuner("second owner");
        StubTunerChannelSource initial = new StubTunerChannelSource(100_000_000L);
        StubTunerChannelSource second = new StubTunerChannelSource(101_000_000L);
        GapTunerManager manager = new GapTunerManager();
        manager.owners.put(initial, firstOwner);
        manager.owners.put(second, secondOwner);
        manager.nextSource = second;
        MultiFrequencyTunerChannelSource wrapper = wrapper(manager, initial);
        StopObserver observer = new StopObserver();
        MyEventBus.getGlobalEventBus().register(observer);

        try
        {
            wrapper.start();
            wrapper.process(SourceEvent.frequencyRotationRequest());
            assertTrue(wrapper.hasSource(second));
            assertEquals(1, second.starts.get());

            firstOwner.setEnabled(false);
            assertEquals(0, observer.count.get(), "the old receiver no longer owns the completed rotation");

            wrapper.process(SourceEvent.frequencyRotationRequest());
            assertEquals(2, manager.allocationAttempts.get());
            secondOwner.setEnabled(false);
            assertTrue(observer.received.await(1, TimeUnit.SECONDS));
            assertSame(wrapper, observer.source);
            assertEquals(1, observer.count.get());
        }
        finally
        {
            wrapper.stop();
            wrapper.dispose();
            MyEventBus.getGlobalEventBus().unregister(observer);
        }
    }

    @Test
    void snapshottedOldOwnerCallbackCannotStopANewAssignment() throws Exception
    {
        GapDiscoveredTuner firstOwner = new GapDiscoveredTuner("first owner");
        GapDiscoveredTuner secondOwner = new GapDiscoveredTuner("second owner");
        StubTunerChannelSource initial = new StubTunerChannelSource(100_000_000L);
        StubTunerChannelSource second = new StubTunerChannelSource(101_000_000L);
        GapTunerManager manager = new GapTunerManager();
        manager.owners.put(initial, firstOwner);
        manager.owners.put(second, secondOwner);
        manager.nextSource = second;
        manager.allocationEntered = new CountDownLatch(1);
        CountDownLatch allowAllocation = new CountDownLatch(1);
        manager.allowAllocation = allowAllocation;
        MultiFrequencyTunerChannelSource wrapper = wrapper(manager, initial);
        StopObserver observer = new StopObserver();
        CountDownLatch quiesceSnapshotStarted = new CountDownLatch(1);
        DiscoveredTuner.LifecycleQuiesceRegistration marker =
            firstOwner.tryRegisterLifecycleQuiesceListener(quiesceSnapshotStarted::countDown);
        MyEventBus.getGlobalEventBus().register(observer);

        try
        {
            assertTrue(marker != null);
            wrapper.start();
            Thread rotation = new Thread(() ->
            {
                try
                {
                    wrapper.process(SourceEvent.frequencyRotationRequest());
                }
                catch(Exception exception)
                {
                    throw new AssertionError(exception);
                }
            }, "rotation-assignment-test");
            rotation.start();
            assertTrue(manager.allocationEntered.await(1, TimeUnit.SECONDS));

            Thread disable = new Thread(() -> firstOwner.setEnabled(false), "old-owner-disable-test");
            disable.start();
            assertTrue(quiesceSnapshotStarted.await(1, TimeUnit.SECONDS));
            allowAllocation.countDown();
            rotation.join(1_000);
            disable.join(1_000);

            assertFalse(rotation.isAlive());
            assertFalse(disable.isAlive());
            assertTrue(wrapper.hasSource(second));
            assertEquals(0, observer.count.get(),
                "a callback snapshotted from the old owner must not stop the newly assigned receiver");
        }
        finally
        {
            allowAllocation.countDown();
            marker.close();
            wrapper.stop();
            wrapper.dispose();
            MyEventBus.getGlobalEventBus().unregister(observer);
        }
    }

    @Test
    void ownerQuiesceStopsSourceLessRotationAndCancelsRetries() throws Exception
    {
        GapDiscoveredTuner owner = new GapDiscoveredTuner("rotation owner");
        StubTunerChannelSource initial = new StubTunerChannelSource(100_000_000L);
        GapTunerManager manager = new GapTunerManager();
        manager.owners.put(initial, owner);
        ChannelSpecification specification = new ChannelSpecification(12_500, 12_500, 5_000, 6_000);
        MultiFrequencyTunerChannelSource wrapper = new MultiFrequencyTunerChannelSource(manager, initial,
            List.of(100_000_000L, 101_000_000L), specification, null, "rotation-test", null, null);
        StopObserver observer = new StopObserver();
        MyEventBus.getGlobalEventBus().register(observer);

        try
        {
            wrapper.start();
            wrapper.process(SourceEvent.frequencyRotationRequest());
            assertEquals(1, initial.stops.get());
            assertEquals(1, manager.allocationAttempts.get());

            owner.setEnabled(false);

            assertTrue(observer.received.await(1, TimeUnit.SECONDS));
            assertSame(wrapper, observer.source);
            Thread.sleep(650);
            assertEquals(1, manager.allocationAttempts.get(),
                "a stopped wrapper must not run its scheduled half-second allocation retry");
            assertFalse(wrapper.hasSource(initial));
        }
        finally
        {
            wrapper.stop();
            wrapper.dispose();
            MyEventBus.getGlobalEventBus().unregister(observer);
        }
    }

    private static MultiFrequencyTunerChannelSource wrapper(GapTunerManager manager,
                                                             StubTunerChannelSource initial)
    {
        ChannelSpecification specification = new ChannelSpecification(12_500, 12_500, 5_000, 6_000);
        return new MultiFrequencyTunerChannelSource(manager, initial,
            List.of(100_000_000L, 101_000_000L), specification, null, "rotation-test", null, null);
    }

    private static class StopObserver
    {
        private final CountDownLatch received = new CountDownLatch(1);
        private final AtomicInteger count = new AtomicInteger();
        private volatile TunerChannelSource source;

        @Subscribe
        public void receive(ChannelStopProcessingRequest request)
        {
            source = request.getTunerChannelSource();
            count.incrementAndGet();
            received.countDown();
        }
    }

    private static class GapTunerManager extends TunerManager
    {
        private final Map<Source,DiscoveredTuner> owners = new IdentityHashMap<>();
        private final AtomicInteger allocationAttempts = new AtomicInteger();
        private volatile Source nextSource;
        private volatile CountDownLatch allocationEntered;
        private volatile CountDownLatch allowAllocation;

        private GapTunerManager()
        {
            super(null);
        }

        @Override
        public Source getSource(TunerChannel tunerChannel, ChannelSpecification channelSpecification,
                                String preferredTuner, String threadName, SortedSet<TunerChannel> tunerChannels)
        {
            allocationAttempts.incrementAndGet();
            CountDownLatch entered = allocationEntered;
            CountDownLatch allow = allowAllocation;

            if(entered != null && allow != null)
            {
                entered.countDown();

                try
                {
                    assertTrue(allow.await(1, TimeUnit.SECONDS));
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }

                allocationEntered = null;
                allowAllocation = null;
            }

            Source source = nextSource;
            nextSource = null;
            return source;
        }

        @Override
        public DiscoveredTuner getSourceAllocationOwner(Source source)
        {
            return owners.get(source);
        }

        @Override
        public void completeSourceAllocation(Source source)
        {
        }
    }

    private static class GapDiscoveredTuner extends DiscoveredTuner
    {
        private final String id;

        private GapDiscoveredTuner(String id)
        {
            this.id = id;
            mTuner = new TestTuner(null);
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.TEST_TUNER;
        }

        @Override
        public String getId()
        {
            return id;
        }

        @Override
        public void start()
        {
        }
    }

    private static class StubTunerChannelSource extends TunerChannelSource
    {
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger stops = new AtomicInteger();
        private Listener<ComplexSamples> listener;

        private StubTunerChannelSource(long frequency)
        {
            super(_ -> {}, new TunerChannel(frequency, 12_500), "stub-source", null);
        }

        @Override
        public void start()
        {
            starts.incrementAndGet();
            super.start();
        }

        @Override
        public void stop()
        {
            stops.incrementAndGet();
            super.stop();
        }

        @Override
        public void setFrequency(long frequency)
        {
            mTunerChannel = new TunerChannel(frequency, mTunerChannel.getBandwidth());
        }

        @Override
        public void setFrequencyCorrection(long correction)
        {
        }

        @Override
        protected void setSampleRate(double sampleRate)
        {
        }

        @Override
        public void setListener(Listener<ComplexSamples> complexSamplesListener)
        {
            listener = complexSamplesListener;
        }

        @Override
        public double getSampleRate()
        {
            return 12_500;
        }
    }
}
