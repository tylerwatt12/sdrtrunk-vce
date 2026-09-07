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
import io.github.dsheirer.source.tuner.TunerType;
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
    private static final long WIDE_MAXIMUM_FREQUENCY = 870_000_000;
    private static final AtomicInteger TUNER_SEQUENCE = new AtomicInteger();

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
    void validRequestFallsBackWhenBroaderEnvelopeIsOutOfRange() throws Exception
    {
        TrackingTunerController controller = createController(857_000_000, WIDE_MAXIMUM_FREQUENCY);
        PolyphaseChannelSourceManager sourceManager = new PolyphaseChannelSourceManager(controller);
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(controller, sourceManager,
            "invalid-envelope-request-fallback");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        controller.clearFrequencyAttempts();
        Source allocated = null;

        try
        {
            allocated = tunerManager.getSource(new TunerChannel(862_000_000, 12_500), CHANNEL_SPECIFICATION,
                null, "invalid-envelope-request-fallback", envelope(849_000_000, 4_000_000));

            assertNotNull(allocated);
            assertEquals(1, sourceManager.getTunerChannelCount());
            assertEquals(862_000_000, controller.getFrequency());
            assertEquals(List.of(862_000_000L), controller.getFrequencyAttempts(),
                "The invalid envelope must be ignored in favor of centering on the valid request");
        }
        finally
        {
            stop(allocated);
            discoveredTuner.stop();
        }
    }

    @Test
    void currentCenterCoverageBeatsEarlierIdleTunerThatWouldRetune() throws Exception
    {
        TrackingTunerController idleController = createController(857_000_000, WIDE_MAXIMUM_FREQUENCY);
        TrackingTunerController currentController = createController(860_000_000, WIDE_MAXIMUM_FREQUENCY);
        PolyphaseChannelSourceManager idleManager = new PolyphaseChannelSourceManager(idleController);
        PolyphaseChannelSourceManager currentManager = new PolyphaseChannelSourceManager(currentController);
        TestDiscoveredTuner idleTuner = new TestDiscoveredTuner(idleController, idleManager, "idle-retune-first");
        TestDiscoveredTuner currentTuner = new TestDiscoveredTuner(currentController, currentManager,
            "busy-current-second");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(idleTuner);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(currentTuner);
        Source existing = currentManager.getSource(new TunerChannel(860_000_000, 12_500), CHANNEL_SPECIFICATION,
            "existing-current-channel");
        Source allocated = null;
        idleController.clearFrequencyAttempts();
        currentController.clearFrequencyAttempts();

        try
        {
            assertNotNull(existing);

            allocated = tunerManager.getSource(new TunerChannel(862_000_000, 12_500), CHANNEL_SPECIFICATION,
                null, "prefer-current-center");

            assertNotNull(allocated);
            assertEquals(0, idleManager.getTunerChannelCount());
            assertEquals(2, currentManager.getTunerChannelCount());
            assertEquals(857_000_000, idleController.getFrequency());
            assertEquals(860_000_000, currentController.getFrequency());
            assertTrue(idleController.getFrequencyAttempts().isEmpty());
            assertTrue(currentController.getFrequencyAttempts().isEmpty());
        }
        finally
        {
            stop(allocated);
            stop(existing);
            idleTuner.stop();
            currentTuner.stop();
        }
    }

    @Test
    void contendedEarlierControllerDoesNotDelayLaterCurrentCenterAllocation() throws Exception
    {
        TrackingTunerController contendedController = createController(857_000_000);
        TrackingTunerController availableController = createController(857_000_000);
        PolyphaseChannelSourceManager contendedManager = new PolyphaseChannelSourceManager(contendedController);
        PolyphaseChannelSourceManager availableManager = new PolyphaseChannelSourceManager(availableController);
        TestDiscoveredTuner contendedTuner = new TestDiscoveredTuner(contendedController, contendedManager,
            "contended-current-first");
        TestDiscoveredTuner availableTuner = new TestDiscoveredTuner(availableController, availableManager,
            "available-current-second");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(contendedTuner);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(availableTuner);
        CountDownLatch controllerLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseControllerLock = new CountDownLatch(1);
        CountDownLatch allocationReturned = new CountDownLatch(1);
        AtomicReference<Source> allocationResult = new AtomicReference<>();
        AtomicReference<Throwable> allocationFailure = new AtomicReference<>();

        Thread lockHolder = new Thread(() -> {
            contendedController.getLock().lock();

            try
            {
                controllerLockAcquired.countDown();
                BlockingChannelSourceManager.await(releaseControllerLock);
            }
            finally
            {
                contendedController.getLock().unlock();
            }
        }, "test contended tuner controller");
        Thread allocation = new Thread(() -> {
            try
            {
                allocationResult.set(tunerManager.getSource(new TunerChannel(859_000_000, 12_500),
                    CHANNEL_SPECIFICATION, null, "skip-contended-controller"));
            }
            catch(Throwable t)
            {
                allocationFailure.set(t);
            }
            finally
            {
                allocationReturned.countDown();
            }
        }, "test allocation past contended controller");

        lockHolder.start();
        assertTrue(controllerLockAcquired.await(2, TimeUnit.SECONDS));
        allocation.start();

        try
        {
            assertTrue(allocationReturned.await(2, TimeUnit.SECONDS),
                "Allocation must skip a contended tuner controller instead of waiting behind it");
            assertTrue(lockHolder.isAlive(), "The first tuner controller should still be contended");
            assertNull(allocationFailure.get());
            assertNotNull(allocationResult.get());
            assertEquals(0, contendedManager.getTunerChannelCount());
            assertEquals(1, availableManager.getTunerChannelCount());
        }
        finally
        {
            releaseControllerLock.countDown();
            lockHolder.join(2_000);
            allocation.join(2_000);
            stop(allocationResult.get());
            contendedTuner.stop();
            availableTuner.stop();
        }

        assertFalse(lockHolder.isAlive());
        assertFalse(allocation.isAlive());
    }

    @Test
    void passThroughTunerOffCenterDoesNotCaptureRequest() throws Exception
    {
        TrackingTunerController passThroughController = createController(860_000_000, WIDE_MAXIMUM_FREQUENCY);
        TrackingTunerController polyphaseController = createController(860_000_000, WIDE_MAXIMUM_FREQUENCY);
        PassThroughSourceManager passThroughManager = new PassThroughSourceManager(passThroughController);
        PolyphaseChannelSourceManager polyphaseManager = new PolyphaseChannelSourceManager(polyphaseController);
        TestDiscoveredTuner passThroughTuner = new TestDiscoveredTuner(passThroughController, passThroughManager,
            "pass-through-outside-first");
        TestDiscoveredTuner polyphaseTuner = new TestDiscoveredTuner(polyphaseController, polyphaseManager,
            "polyphase-current-second");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(passThroughTuner);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(polyphaseTuner);
        Source allocated = null;
        passThroughController.clearFrequencyAttempts();
        polyphaseController.clearFrequencyAttempts();

        try
        {
            allocated = tunerManager.getSource(new TunerChannel(862_000_000, 12_500), CHANNEL_SPECIFICATION,
                null, "skip-outside-pass-through-window");

            assertNotNull(allocated);
            assertEquals(0, passThroughManager.getTunerChannelCount());
            assertEquals(1, polyphaseManager.getTunerChannelCount());
            assertEquals(860_000_000, passThroughController.getFrequency());
            assertEquals(860_000_000, polyphaseController.getFrequency());
            assertTrue(passThroughController.getFrequencyAttempts().isEmpty());
            assertTrue(polyphaseController.getFrequencyAttempts().isEmpty());
        }
        finally
        {
            stop(allocated);
            passThroughTuner.stop();
            polyphaseTuner.stop();
        }
    }

    @Test
    void passThroughTunerAtExactCenterParticipatesInCurrentCenterStage() throws Exception
    {
        TrackingTunerController passThroughController = createController(860_000_000, WIDE_MAXIMUM_FREQUENCY);
        TrackingTunerController polyphaseController = createController(860_000_000, WIDE_MAXIMUM_FREQUENCY);
        PassThroughSourceManager passThroughManager = new PassThroughSourceManager(passThroughController);
        PolyphaseChannelSourceManager polyphaseManager = new PolyphaseChannelSourceManager(polyphaseController);
        TestDiscoveredTuner passThroughTuner = new TestDiscoveredTuner(passThroughController, passThroughManager,
            "pass-through-exact-center-first");
        TestDiscoveredTuner polyphaseTuner = new TestDiscoveredTuner(polyphaseController, polyphaseManager,
            "polyphase-current-second");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(passThroughTuner);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(polyphaseTuner);
        Source allocated = null;
        passThroughController.clearFrequencyAttempts();
        polyphaseController.clearFrequencyAttempts();

        try
        {
            allocated = tunerManager.getSource(new TunerChannel(860_000_000, 12_500), CHANNEL_SPECIFICATION,
                null, "exact-center-pass-through");

            assertNotNull(allocated);
            assertEquals(1, passThroughManager.getTunerChannelCount());
            assertEquals(0, polyphaseManager.getTunerChannelCount());
            assertEquals(860_000_000, passThroughController.getFrequency());
            assertEquals(860_000_000, polyphaseController.getFrequency());
            assertTrue(passThroughController.getFrequencyAttempts().isEmpty());
            assertTrue(polyphaseController.getFrequencyAttempts().isEmpty());
        }
        finally
        {
            stop(allocated);
            passThroughTuner.stop();
            polyphaseTuner.stop();
        }
    }

    @Test
    void preferredTunerWinsWithinCurrentCenterStage() throws Exception
    {
        TrackingTunerController firstController = createController(857_000_000);
        TrackingTunerController preferredController = createController(857_000_000);
        PolyphaseChannelSourceManager firstManager = new PolyphaseChannelSourceManager(firstController);
        PolyphaseChannelSourceManager preferredManager = new PolyphaseChannelSourceManager(preferredController);
        TestDiscoveredTuner firstTuner = new TestDiscoveredTuner(firstController, firstManager, "current-first");
        TestDiscoveredTuner preferredTuner = new TestDiscoveredTuner(preferredController, preferredManager,
            "current-preferred");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(firstTuner);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(preferredTuner);
        firstController.clearFrequencyAttempts();
        preferredController.clearFrequencyAttempts();
        Source allocated = null;

        try
        {
            allocated = tunerManager.getSource(new TunerChannel(859_000_000, 12_500), CHANNEL_SPECIFICATION,
                preferredTuner.getId(), "preferred-current-center");

            assertNotNull(allocated);
            assertEquals(0, firstManager.getTunerChannelCount());
            assertEquals(1, preferredManager.getTunerChannelCount());
            assertTrue(firstController.getFrequencyAttempts().isEmpty());
            assertTrue(preferredController.getFrequencyAttempts().isEmpty());
        }
        finally
        {
            stop(allocated);
            firstTuner.stop();
            preferredTuner.stop();
        }
    }

    @Test
    void preferredTunerThatNeedsRetuneLosesToCurrentCenterCoverage() throws Exception
    {
        TrackingTunerController preferredController = createController(857_000_000, WIDE_MAXIMUM_FREQUENCY);
        TrackingTunerController currentController = createController(860_000_000, WIDE_MAXIMUM_FREQUENCY);
        PolyphaseChannelSourceManager preferredManager = new PolyphaseChannelSourceManager(preferredController);
        PolyphaseChannelSourceManager currentManager = new PolyphaseChannelSourceManager(currentController);
        TestDiscoveredTuner preferredTuner = new TestDiscoveredTuner(preferredController, preferredManager,
            "retune-preferred");
        TestDiscoveredTuner currentTuner = new TestDiscoveredTuner(currentController, currentManager,
            "current-nonpreferred");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(preferredTuner);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(currentTuner);
        preferredController.clearFrequencyAttempts();
        currentController.clearFrequencyAttempts();
        Source allocated = null;

        try
        {
            allocated = tunerManager.getSource(new TunerChannel(862_000_000, 12_500), CHANNEL_SPECIFICATION,
                preferredTuner.getId(), "safe-stage-before-preference");

            assertNotNull(allocated);
            assertEquals(0, preferredManager.getTunerChannelCount());
            assertEquals(1, currentManager.getTunerChannelCount());
            assertEquals(857_000_000, preferredController.getFrequency());
            assertEquals(860_000_000, currentController.getFrequency());
            assertTrue(preferredController.getFrequencyAttempts().isEmpty());
            assertTrue(currentController.getFrequencyAttempts().isEmpty());
        }
        finally
        {
            stop(allocated);
            preferredTuner.stop();
            currentTuner.stop();
        }
    }

    @Test
    void idleTunerUsesBroaderEnvelopeForInitialPlacement() throws Exception
    {
        TrackingTunerController controller = createController(852_500_000);
        PolyphaseChannelSourceManager sourceManager = new PolyphaseChannelSourceManager(controller);
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(controller, sourceManager,
            "idle-envelope-placement");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        controller.clearFrequencyAttempts();
        Source allocated = null;

        try
        {
            allocated = tunerManager.getSource(new TunerChannel(855_000_000, 12_500), CHANNEL_SPECIFICATION,
                null, "staged-envelope-placement", envelope(857_000_000, 8_000_000));

            assertNotNull(allocated);
            assertEquals(857_000_000, controller.getFrequency());
            assertEquals(List.of(857_000_000L), controller.getFrequencyAttempts());
        }
        finally
        {
            stop(allocated);
            discoveredTuner.stop();
        }
    }

    @Test
    void idleRetuneBeatsBusyRetune() throws Exception
    {
        TrackingTunerController busyController = createController(857_000_000, WIDE_MAXIMUM_FREQUENCY);
        TrackingTunerController idleController = createController(857_000_000, WIDE_MAXIMUM_FREQUENCY);
        PolyphaseChannelSourceManager busyManager = new PolyphaseChannelSourceManager(busyController);
        PolyphaseChannelSourceManager idleManager = new PolyphaseChannelSourceManager(idleController);
        TestDiscoveredTuner busyTuner = new TestDiscoveredTuner(busyController, busyManager, "busy-retune-first");
        TestDiscoveredTuner idleTuner = new TestDiscoveredTuner(idleController, idleManager, "idle-retune-second");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(busyTuner);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(idleTuner);
        Source existing = busyManager.getSource(new TunerChannel(857_000_000, 12_500), CHANNEL_SPECIFICATION,
            "existing-busy-channel");
        Source allocated = null;
        busyController.clearFrequencyAttempts();
        idleController.clearFrequencyAttempts();

        try
        {
            assertNotNull(existing);

            allocated = tunerManager.getSource(new TunerChannel(862_000_000, 12_500), CHANNEL_SPECIFICATION,
                null, "idle-before-busy-retune");

            assertNotNull(allocated);
            assertEquals(1, busyManager.getTunerChannelCount());
            assertEquals(1, idleManager.getTunerChannelCount());
            assertEquals(857_000_000, busyController.getFrequency());
            assertTrue(busyController.getFrequencyAttempts().isEmpty());
            assertFalse(idleController.getFrequencyAttempts().isEmpty());
        }
        finally
        {
            stop(allocated);
            stop(existing);
            busyTuner.stop();
            idleTuner.stop();
        }
    }

    @Test
    void busyTunerRetunesOnlyAfterSaferStagesAndKeepsExistingChannel() throws Exception
    {
        TrackingTunerController controller = createController(857_000_000, WIDE_MAXIMUM_FREQUENCY);
        PolyphaseChannelSourceManager sourceManager = new PolyphaseChannelSourceManager(controller);
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(controller, sourceManager,
            "busy-retune-last-resort");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        Source existing = sourceManager.getSource(new TunerChannel(857_000_000, 12_500), CHANNEL_SPECIFICATION,
            "existing-retained-channel");
        Source allocated = null;
        controller.clearFrequencyAttempts();

        try
        {
            assertNotNull(existing);

            allocated = tunerManager.getSource(new TunerChannel(862_000_000, 12_500), CHANNEL_SPECIFICATION,
                null, "busy-retune-last-stage");

            assertNotNull(allocated);
            assertEquals(2, sourceManager.getTunerChannelCount());
            assertTrue(sourceManager.getTunerChannels().stream().anyMatch(channel ->
                channel.getFrequency() == 857_000_000));
            assertTrue(sourceManager.getTunerChannels().stream().anyMatch(channel ->
                channel.getFrequency() == 862_000_000));
            assertFalse(controller.getFrequencyAttempts().isEmpty());
        }
        finally
        {
            stop(allocated);
            stop(existing);
            discoveredTuner.stop();
        }
    }

    @Test
    void busyTunerRejectsChannelSetWiderThanUsableBandwidthWithoutRetuning() throws Exception
    {
        TrackingTunerController controller = createController(857_000_000, WIDE_MAXIMUM_FREQUENCY);
        PolyphaseChannelSourceManager sourceManager = new PolyphaseChannelSourceManager(controller);
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(controller, sourceManager,
            "busy-span-too-wide");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        Source existing = sourceManager.getSource(new TunerChannel(857_000_000, 12_500), CHANNEL_SPECIFICATION,
            "existing-wide-span-channel");
        controller.clearFrequencyAttempts();

        try
        {
            assertNotNull(existing);

            Source allocated = tunerManager.getSource(new TunerChannel(867_000_000, 12_500),
                CHANNEL_SPECIFICATION, null, "reject-wide-busy-span");

            assertNull(allocated);
            assertEquals(1, sourceManager.getTunerChannelCount(),
                "Rejecting the new request must leave the existing source allocated");
            assertTrue(sourceManager.getTunerChannels().stream().anyMatch(channel ->
                channel.getFrequency() == 857_000_000));
            assertEquals(857_000_000, controller.getFrequency());
            assertTrue(controller.getFrequencyAttempts().isEmpty(),
                "An impossible active-plus-new span must be rejected before a hardware retune");
        }
        finally
        {
            stop(existing);
            discoveredTuner.stop();
        }
    }

    @Test
    void centerLockedTunerAcceptsCurrentCoverageWithoutRetuning() throws Exception
    {
        TrackingTunerController controller = createController(857_000_000);
        controller.setCenterFrequencyLocked(true);
        PolyphaseChannelSourceManager sourceManager = new PolyphaseChannelSourceManager(controller);
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(controller, sourceManager,
            "locked-current-coverage");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        controller.clearFrequencyAttempts();
        Source allocated = null;

        try
        {
            allocated = tunerManager.getSource(new TunerChannel(859_000_000, 12_500), CHANNEL_SPECIFICATION,
                null, "locked-current-fit");

            assertNotNull(allocated);
            assertEquals(857_000_000, controller.getFrequency());
            assertTrue(controller.getFrequencyAttempts().isEmpty());
        }
        finally
        {
            stop(allocated);
            discoveredTuner.stop();
        }
    }

    @Test
    void centerLockedTunerRejectsOutsideCoverageAndPreservesLimits() throws Exception
    {
        TrackingTunerController controller = createController(857_000_000, WIDE_MAXIMUM_FREQUENCY);
        controller.setCenterFrequencyLocked(true);
        long minimumFrequency = controller.getMinimumFrequency();
        long maximumFrequency = controller.getMaximumFrequency();
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(controller, new PolyphaseChannelSourceManager(
            controller), "locked-outside-coverage");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        controller.clearFrequencyAttempts();

        try
        {
            Source allocated = tunerManager.getSource(new TunerChannel(862_000_000, 12_500), CHANNEL_SPECIFICATION,
                null, "locked-outside-fit");

            assertNull(allocated);
            assertEquals(857_000_000, controller.getFrequency());
            assertEquals(minimumFrequency, controller.getMinimumFrequency());
            assertEquals(maximumFrequency, controller.getMaximumFrequency());
            assertTrue(controller.getFrequencyAttempts().isEmpty());
        }
        finally
        {
            discoveredTuner.stop();
        }
    }

    @Test
    void retunePreservesConfiguredFrequencyLimits() throws Exception
    {
        TrackingTunerController controller = createController(857_000_000, WIDE_MAXIMUM_FREQUENCY);
        long minimumFrequency = controller.getMinimumFrequency();
        long maximumFrequency = controller.getMaximumFrequency();
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(controller, new PolyphaseChannelSourceManager(
            controller), "retune-preserves-limits");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        controller.clearFrequencyAttempts();
        Source allocated = null;

        try
        {
            allocated = tunerManager.getSource(new TunerChannel(862_000_000, 12_500), CHANNEL_SPECIFICATION,
                null, "retune-keeps-limits");

            assertNotNull(allocated);
            assertFalse(controller.getFrequencyAttempts().isEmpty());
            assertEquals(minimumFrequency, controller.getMinimumFrequency());
            assertEquals(maximumFrequency, controller.getMaximumFrequency());
        }
        finally
        {
            stop(allocated);
            discoveredTuner.stop();
        }
    }

    @Test
    void failedHardwareRetuneDoesNotAllocateOrAdvanceSoftwareCenter() throws Exception
    {
        FailingRetuneTunerController controller = new FailingRetuneTunerController();
        controller.setSampleRate(10_000_000);
        controller.setUsableBandwidthPercentage(0.90);
        controller.setFrequency(857_000_000);
        controller.setMinimumFrequency(MINIMUM_FREQUENCY);
        controller.setMaximumFrequency(WIDE_MAXIMUM_FREQUENCY);
        PolyphaseChannelSourceManager sourceManager = new PolyphaseChannelSourceManager(controller);
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(controller, sourceManager,
            "failed-hardware-retune");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        controller.failHardwareTunesTo(862_000_000);
        controller.clearFrequencyAttempts();

        try
        {
            Source allocated = tunerManager.getSource(new TunerChannel(862_000_000, 12_500),
                CHANNEL_SPECIFICATION, null, "failed-hardware-retune");

            assertNull(allocated);
            assertEquals(0, sourceManager.getTunerChannelCount());
            assertEquals(857_000_000, controller.getFrequency(),
                "A failed hardware tune must not advance the software center");
            assertEquals(857_000_000, controller.getTunedFrequency());
            assertEquals(2, controller.getFailedHardwareTuneAttempts(),
                "The later retry stage must still see the old center and attempt the hardware tune again");
            assertEquals(List.of(862_000_000L, 862_000_000L), controller.getFrequencyAttempts());
        }
        finally
        {
            discoveredTuner.stop();
        }
    }

    @Test
    void failedHardwareRetuneFallsThroughToLaterEligibleTuner() throws Exception
    {
        FailingRetuneTunerController failingController = new FailingRetuneTunerController();
        failingController.setSampleRate(10_000_000);
        failingController.setUsableBandwidthPercentage(0.90);
        failingController.setFrequency(857_000_000);
        failingController.setMinimumFrequency(MINIMUM_FREQUENCY);
        failingController.setMaximumFrequency(WIDE_MAXIMUM_FREQUENCY);
        TrackingTunerController fallbackController = createController(857_000_000, WIDE_MAXIMUM_FREQUENCY);
        PolyphaseChannelSourceManager failingManager = new PolyphaseChannelSourceManager(failingController);
        PolyphaseChannelSourceManager fallbackManager = new PolyphaseChannelSourceManager(fallbackController);
        TestDiscoveredTuner failingTuner = new TestDiscoveredTuner(failingController, failingManager,
            "failed-retune-first");
        TestDiscoveredTuner fallbackTuner = new TestDiscoveredTuner(fallbackController, fallbackManager,
            "fallback-retune-second");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(failingTuner);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(fallbackTuner);
        failingController.failHardwareTunesTo(862_000_000);
        failingController.clearFrequencyAttempts();
        fallbackController.clearFrequencyAttempts();
        Source allocated = null;

        try
        {
            allocated = tunerManager.getSource(new TunerChannel(862_000_000, 12_500), CHANNEL_SPECIFICATION,
                null, "fall-through-after-failed-retune");

            assertNotNull(allocated);
            assertEquals(0, failingManager.getTunerChannelCount());
            assertEquals(1, fallbackManager.getTunerChannelCount());
            assertEquals(857_000_000, failingController.getFrequency());
            assertEquals(862_000_000, fallbackController.getFrequency());
            assertEquals(1, failingController.getFailedHardwareTuneAttempts());
            assertEquals(List.of(862_000_000L), failingController.getFrequencyAttempts());
            assertEquals(List.of(862_000_000L), fallbackController.getFrequencyAttempts());
        }
        finally
        {
            stop(allocated);
            failingTuner.stop();
            fallbackTuner.stop();
        }
    }

    @Test
    void transientLifecycleContentionRetriesCurrentCoverageWithoutRetuning() throws Exception
    {
        TrackingTunerController controller = createController(857_000_000);
        controller.setCenterFrequencyLocked(true);
        PolyphaseChannelSourceManager sourceManager = new PolyphaseChannelSourceManager(controller);
        RetryAllocationDiscoveredTuner discoveredTuner = new RetryAllocationDiscoveredTuner(controller,
            sourceManager, "lifecycle-retry-current");
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        controller.clearFrequencyAttempts();
        Source allocated = null;

        try
        {
            allocated = tunerManager.getSource(new TunerChannel(859_000_000, 12_500), CHANNEL_SPECIFICATION,
                null, "lifecycle-retry");

            assertNotNull(allocated);
            assertEquals(2, discoveredTuner.getAllocationAttempts());
            assertEquals(1, sourceManager.getTunerChannelCount());
            assertEquals(857_000_000, controller.getFrequency());
            assertTrue(controller.getFrequencyAttempts().isEmpty());
        }
        finally
        {
            stop(allocated);
            discoveredTuner.stop();
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
    void directStopWaitsForInFlightAllocationBeforeTeardown() throws Exception
    {
        TrackingTunerController controller = createController(857_000_000);
        BlockingChannelSourceManager sourceManager = new BlockingChannelSourceManager();
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(controller, sourceManager);
        TunerManager tunerManager = new TunerManager(null);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        AtomicReference<Throwable> allocationFailure = new AtomicReference<>();
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        CountDownLatch stopStarted = new CountDownLatch(1);

        Thread allocation = new Thread(() -> {
            try
            {
                tunerManager.getSource(new TunerChannel(857_000_000, 12_500), CHANNEL_SPECIFICATION,
                    null, "direct-stop-overlap");
            }
            catch(Throwable t)
            {
                allocationFailure.set(t);
            }
        }, "test allocation before direct tuner stop");
        Thread directStop = new Thread(() -> {
            stopStarted.countDown();

            try
            {
                discoveredTuner.stop();
            }
            catch(Throwable t)
            {
                stopFailure.set(t);
            }
        }, "test direct tuner stop");

        try
        {
            allocation.start();
            assertTrue(sourceManager.mAllocationEntered.await(2, TimeUnit.SECONDS));
            directStop.start();
            assertTrue(stopStarted.await(2, TimeUnit.SECONDS));
            assertTrue(awaitLifecycleWait(directStop),
                "Direct stop must wait for the in-flight allocation lifecycle boundary");
            assertEquals(1, sourceManager.mStopEntered.getCount(),
                "Tuner teardown must not start while allocation is active");

            sourceManager.mReleaseAllocation.countDown();
            assertTrue(sourceManager.mStopEntered.await(2, TimeUnit.SECONDS));
            assertTrue(directStop.isAlive(), "Direct stop should remain in teardown until the test releases it");
        }
        finally
        {
            sourceManager.mReleaseAllocation.countDown();
            sourceManager.mReleaseStop.countDown();
            allocation.join(2_000);
            directStop.join(2_000);
        }

        assertFalse(allocation.isAlive());
        assertFalse(directStop.isAlive());
        assertNull(allocationFailure.get());
        assertNull(stopFailure.get());
        assertFalse(discoveredTuner.hasTuner());
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
        return createController(centerFrequency, MAXIMUM_FREQUENCY);
    }

    private static TrackingTunerController createController(long centerFrequency, long maximumFrequency)
        throws SourceException
    {
        TrackingTunerController controller = new TrackingTunerController();
        controller.setSampleRate(10_000_000);
        controller.setUsableBandwidthPercentage(0.90);
        controller.setFrequency(centerFrequency);
        controller.setMinimumFrequency(MINIMUM_FREQUENCY);
        controller.setMaximumFrequency(maximumFrequency);
        return controller;
    }

    private static void stop(Source source)
    {
        if(source != null)
        {
            source.stop();
        }
    }

    private static String nextTunerId()
    {
        return "allocation-test-" + TUNER_SEQUENCE.incrementAndGet();
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

        List<Long> getFrequencyAttempts()
        {
            return List.copyOf(mFrequencyAttempts);
        }

        void clearFrequencyAttempts()
        {
            mFrequencyAttempts.clear();
        }
    }

    private static class FailingRetuneTunerController extends TrackingTunerController
    {
        private final AtomicInteger mFailedHardwareTuneAttempts = new AtomicInteger();
        private long mFailureFrequency = Long.MIN_VALUE;

        private void failHardwareTunesTo(long frequency)
        {
            mFailureFrequency = frequency;
        }

        @Override
        public void setTunedFrequency(long frequency) throws SourceException
        {
            if(frequency == mFailureFrequency)
            {
                mFailedHardwareTuneAttempts.incrementAndGet();
                throw new SourceException("Injected hardware tune failure");
            }

            super.setTunedFrequency(frequency);
        }

        private int getFailedHardwareTuneAttempts()
        {
            return mFailedHardwareTuneAttempts.get();
        }
    }

    private static class TestDiscoveredTuner extends DiscoveredTuner
    {
        private final TrackingTunerController mController;
        private final ChannelSourceManager mSourceManager;
        private final String mId;

        private TestDiscoveredTuner(TrackingTunerController controller)
        {
            this(controller, new PolyphaseChannelSourceManager(controller), nextTunerId());
        }

        private TestDiscoveredTuner(TrackingTunerController controller, ChannelSourceManager sourceManager)
        {
            this(controller, sourceManager, nextTunerId());
        }

        private TestDiscoveredTuner(TrackingTunerController controller, ChannelSourceManager sourceManager, String id)
        {
            mController = controller;
            mSourceManager = sourceManager;
            mId = id;
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
            return mId;
        }

        @Override
        public void start()
        {
            if(!hasTuner())
            {
                mTuner = new AllocationTestTuner(mController, this, mSourceManager, mId);

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

    private static class RetryAllocationDiscoveredTuner extends TestDiscoveredTuner
    {
        private final AtomicInteger mAllocationAttempts = new AtomicInteger();

        private RetryAllocationDiscoveredTuner(TrackingTunerController controller,
                                               ChannelSourceManager sourceManager, String id)
        {
            super(controller, sourceManager, id);
        }

        @Override
        boolean tryAcquireForAllocation()
        {
            if(mAllocationAttempts.incrementAndGet() == 1)
            {
                return false;
            }

            return super.tryAcquireForAllocation();
        }

        private int getAllocationAttempts()
        {
            return mAllocationAttempts.get();
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
        private final String mId;

        private AllocationTestTuner(TrackingTunerController controller, ITunerErrorListener tunerErrorListener,
                                    ChannelSourceManager sourceManager, String id)
        {
            super(controller, tunerErrorListener, sourceManager);
            mId = id;
        }

        @Override
        public int getMaximumUSBBitsPerSecond()
        {
            return 0;
        }

        @Override
        public String getUniqueID()
        {
            return mId;
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.TEST_TUNER;
        }

        @Override
        public TunerType getTunerType()
        {
            //Allocation tests do not need to create or persist hardware-specific tuner configurations.
            return TunerType.RECORDING;
        }

        @Override
        public String getPreferredName()
        {
            return mId;
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
