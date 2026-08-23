/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.test.TestTunerController;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TunerManagerAllocationTest
{
    private static final ChannelSpecification CHANNEL_SPECIFICATION =
        new ChannelSpecification(50_000, 12_500, 6_250, 7_000);
    private static final long MINIMUM_FREQUENCY = 852_000_000;
    private static final long MAXIMUM_FREQUENCY = 862_000_000;

    @Test
    void outOfRangeEnvelopeDoesNotAttemptToRetuneTuner() throws Exception
    {
        TrackingTunerController controller = createController(857_000_000);
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(controller);
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        controller.clearFrequencyAttempts();

        try
        {
            Source source = tunerManager.getSource(new TunerChannel(771_806_250, 12_500), CHANNEL_SPECIFICATION,
                null, "out-of-range-envelope", envelope(771_800_000, 4_000_000));

            assertNull(source);
            assertEquals(857_000_000, controller.getFrequency());
            assertTrue(controller.getFrequencyAttempts().isEmpty(),
                "An out-of-range request must be rejected before attempting to move the tuner center");
        }
        finally
        {
            discoveredTuner.stop();
        }
    }

    @Test
    void inRangeEnvelopeStillCentersAndAllocatesTuner() throws Exception
    {
        TrackingTunerController controller = createController(852_500_000);
        PolyphaseChannelSourceManager sourceManager = new PolyphaseChannelSourceManager(controller);
        controller.clearFrequencyAttempts();

        Source source = null;

        try
        {
            source = sourceManager.getSource(new TunerChannel(855_000_000, 12_500), CHANNEL_SPECIFICATION,
                "in-range-envelope", envelope(857_000_000, 8_000_000));

            assertNotNull(source);
            assertEquals(857_000_000, controller.getFrequency());
            assertEquals(List.of(857_000_000L), controller.getFrequencyAttempts());
        }
        finally
        {
            if(source != null)
            {
                source.stop();
            }

            sourceManager.dispose();
        }
    }

    @Test
    void disablingTunerWaitsForAllocationAndMakesItUnavailableBeforeTeardown() throws Exception
    {
        TrackingTunerController controller = createController(857_000_000);
        BlockingChannelSourceManager sourceManager = new BlockingChannelSourceManager();
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(controller, sourceManager);
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        AtomicReference<Throwable> allocationFailure = new AtomicReference<>();
        AtomicReference<Throwable> disableFailure = new AtomicReference<>();
        CountDownLatch disableStarted = new CountDownLatch(1);

        Thread allocation = new Thread(() -> {
            try
            {
                tunerManager.getSource(new TunerChannel(857_000_000, 12_500), CHANNEL_SPECIFICATION,
                    null, "disable-overlap");
            }
            catch(Throwable t)
            {
                allocationFailure.set(t);
            }
        }, "test tuner allocation");
        Thread disable = new Thread(() -> {
            disableStarted.countDown();

            try
            {
                discoveredTuner.setEnabled(false);
            }
            catch(Throwable t)
            {
                disableFailure.set(t);
            }
        }, "test tuner disable");

        allocation.start();
        assertTrue(sourceManager.mAllocationEntered.await(2, TimeUnit.SECONDS));
        disable.start();
        assertTrue(disableStarted.await(2, TimeUnit.SECONDS));
        assertTrue(awaitLifecycleWait(disable),
            "Disable must wait for the in-flight allocation lifecycle boundary");
        assertEquals(1, sourceManager.mStopEntered.getCount(),
            "Tuner teardown must not start while allocation is active");

        sourceManager.mReleaseAllocation.countDown();
        assertTrue(sourceManager.mStopEntered.await(2, TimeUnit.SECONDS));
        assertEquals(TunerStatus.DISABLED, discoveredTuner.getTunerStatus());
        assertTrue(tunerManager.getAvailableTuners().isEmpty(),
            "A disabled tuner must disappear from allocation before teardown starts");
        sourceManager.mReleaseStop.countDown();

        allocation.join(2_000);
        disable.join(2_000);
        assertFalse(allocation.isAlive());
        assertFalse(disable.isAlive());
        assertNull(allocationFailure.get());
        assertNull(disableFailure.get());
        assertNull(tunerManager.getSource(new TunerChannel(857_000_000, 12_500), CHANNEL_SPECIFICATION,
            null, "after-disable"));
    }

    @Test
    void allocationDoesNotWaitBehindDisableHardwareTeardown() throws Exception
    {
        TrackingTunerController controller = createController(857_000_000);
        BlockingChannelSourceManager sourceManager = new BlockingChannelSourceManager();
        CoordinatedDiscoveredTuner discoveredTuner = new CoordinatedDiscoveredTuner(controller, sourceManager);
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        AtomicReference<Source> allocationResult = new AtomicReference<>();
        AtomicReference<Throwable> allocationFailure = new AtomicReference<>();
        AtomicReference<Throwable> disableFailure = new AtomicReference<>();
        CountDownLatch allocationReturned = new CountDownLatch(1);
        discoveredTuner.coordinateNextAllocation();

        Thread allocation = new Thread(() -> {
            try
            {
                allocationResult.set(tunerManager.getSource(new TunerChannel(857_000_000, 12_500),
                    CHANNEL_SPECIFICATION, null, "disable-reverse-overlap"));
            }
            catch(Throwable t)
            {
                allocationFailure.set(t);
            }
            finally
            {
                allocationReturned.countDown();
            }
        }, "test decoder-side tuner allocation");
        Thread disable = new Thread(() -> {
            try
            {
                discoveredTuner.setEnabled(false);
            }
            catch(Throwable t)
            {
                disableFailure.set(t);
            }
        }, "test tuner disable teardown");

        try
        {
            allocation.start();
            assertTrue(discoveredTuner.mBeforeLifecycleReservation.await(2, TimeUnit.SECONDS));
            disable.start();
            assertTrue(sourceManager.mStopEntered.await(2, TimeUnit.SECONDS));

            discoveredTuner.mContinueAllocation.countDown();
            assertTrue(allocationReturned.await(2, TimeUnit.SECONDS),
                "Decoder-side allocation must fail fast while tuner hardware teardown owns the lifecycle");
            assertNull(allocationResult.get());
            assertNull(allocationFailure.get());
            assertEquals(1, sourceManager.mAllocationEntered.getCount(),
                "A busy tuner lifecycle must be rejected before channel-source allocation");
            assertTrue(disable.isAlive(), "Hardware teardown should remain blocked until the test releases it");
        }
        finally
        {
            discoveredTuner.mContinueAllocation.countDown();
            sourceManager.mReleaseAllocation.countDown();
            sourceManager.mReleaseStop.countDown();
            allocation.join(2_000);
            disable.join(2_000);
        }

        assertFalse(allocation.isAlive());
        assertFalse(disable.isAlive());
        assertNull(disableFailure.get());
    }

    private static boolean awaitLifecycleWait(Thread thread) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while(System.nanoTime() < deadline)
        {
            if(thread.getState() == Thread.State.BLOCKED || thread.getState() == Thread.State.WAITING)
            {
                return true;
            }

            Thread.sleep(1);
        }

        return false;
    }

    private static TrackingTunerController createController(long centerFrequency) throws SourceException
    {
        TrackingTunerController controller = new TrackingTunerController();
        controller.setSampleRate(10_000_000);
        controller.setUsableBandwidthPercentage(0.90);
        controller.setFrequency(centerFrequency);
        controller.setMinimumFrequency(MINIMUM_FREQUENCY);
        controller.setMaximumFrequency(MAXIMUM_FREQUENCY);
        return controller;
    }

    private static SortedSet<TunerChannel> envelope(long centerFrequency, int bandwidth)
    {
        SortedSet<TunerChannel> channels = new TreeSet<>();
        channels.add(new TunerChannel(centerFrequency, bandwidth));
        return channels;
    }

    private static class TrackingTunerController extends TestTunerController
    {
        private final List<Long> mFrequencyAttempts = new ArrayList<>();

        @Override
        public void setFrequency(long frequency) throws SourceException
        {
            mFrequencyAttempts.add(frequency);
            super.setFrequency(frequency);
        }

        @Override
        public void addBufferListener(Listener<INativeBuffer> listener)
        {
            //No sample stream is needed for allocation-only tests.
        }

        @Override
        public void removeBufferListener(Listener<INativeBuffer> listener)
        {
            //No sample stream is used for allocation-only tests.
        }

        private List<Long> getFrequencyAttempts()
        {
            return List.copyOf(mFrequencyAttempts);
        }

        private void clearFrequencyAttempts()
        {
            mFrequencyAttempts.clear();
        }
    }

    private static class TestDiscoveredTuner extends DiscoveredTuner
    {
        private final TrackingTunerController mController;
        private final ChannelSourceManager mSourceManager;

        private TestDiscoveredTuner(TrackingTunerController controller)
        {
            this(controller, new PolyphaseChannelSourceManager(controller));
        }

        private TestDiscoveredTuner(TrackingTunerController controller, ChannelSourceManager sourceManager)
        {
            mController = controller;
            mSourceManager = sourceManager;
            start();
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.TEST_TUNER;
        }

        @Override
        public String getId()
        {
            return "allocation-test";
        }

        @Override
        public void start()
        {
            if(!hasTuner())
            {
                mTuner = new AllocationTestTuner(mController, this, mSourceManager);

                try
                {
                    mTuner.start();
                }
                catch(SourceException se)
                {
                    throw new IllegalStateException("Unable to start allocation test tuner", se);
                }
            }
        }
    }

    private static class CoordinatedDiscoveredTuner extends TestDiscoveredTuner
    {
        private final AtomicBoolean mCoordinateAllocation = new AtomicBoolean();
        private final AtomicInteger mHasTunerChecks = new AtomicInteger();
        private final CountDownLatch mBeforeLifecycleReservation = new CountDownLatch(1);
        private final CountDownLatch mContinueAllocation = new CountDownLatch(1);

        private CoordinatedDiscoveredTuner(TrackingTunerController controller, ChannelSourceManager sourceManager)
        {
            super(controller, sourceManager);
        }

        private void coordinateNextAllocation()
        {
            mHasTunerChecks.set(0);
            mCoordinateAllocation.set(true);
        }

        @Override
        public boolean hasTuner()
        {
            boolean hasTuner = super.hasTuner();

            //The first check builds TunerManager's available-tuner snapshot.  Pause on the second check, immediately
            //before it reserves the lifecycle, so disable can begin hardware teardown in that exact race window.
            if(mCoordinateAllocation != null && mCoordinateAllocation.get() && mHasTunerChecks.incrementAndGet() == 2 &&
                mCoordinateAllocation.compareAndSet(true, false))
            {
                mBeforeLifecycleReservation.countDown();
                BlockingChannelSourceManager.await(mContinueAllocation);
            }

            return hasTuner;
        }
    }

    private static class AllocationTestTuner extends Tuner
    {
        private AllocationTestTuner(TrackingTunerController controller, ITunerErrorListener tunerErrorListener,
                                    ChannelSourceManager sourceManager)
        {
            super(controller, tunerErrorListener, sourceManager);
        }

        @Override
        public int getMaximumUSBBitsPerSecond()
        {
            return 0;
        }

        @Override
        public String getUniqueID()
        {
            return "allocation-test";
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.TEST_TUNER;
        }

        @Override
        public String getPreferredName()
        {
            return "Allocation Test Tuner";
        }

        @Override
        public double getSampleSize()
        {
            return 16.0;
        }
    }

    private static class BlockingChannelSourceManager extends ChannelSourceManager
    {
        private final CountDownLatch mAllocationEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseAllocation = new CountDownLatch(1);
        private final CountDownLatch mStopEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseStop = new CountDownLatch(1);

        @Override
        public SortedSet<TunerChannel> getTunerChannels()
        {
            return new TreeSet<>();
        }

        @Override
        public String getStateDescription()
        {
            return "blocking lifecycle test";
        }

        @Override
        public int getTunerChannelCount()
        {
            return 0;
        }

        @Override
        public void stopAllChannels()
        {
            mStopEntered.countDown();
            await(mReleaseStop);
        }

        @Override
        public TunerChannelSource getSource(TunerChannel tunerChannel, ChannelSpecification channelSpecification,
                                            String threadName)
        {
            mAllocationEntered.countDown();
            await(mReleaseAllocation);
            return null;
        }

        @Override
        public void setErrorMessage(String errorMessage)
        {
        }

        @Override
        public void process(SourceEvent sourceEvent)
        {
        }

        private static void await(CountDownLatch latch)
        {
            try
            {
                latch.await();
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
