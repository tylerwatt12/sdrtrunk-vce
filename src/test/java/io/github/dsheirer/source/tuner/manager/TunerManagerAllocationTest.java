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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.test.TestTunerController;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
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

        private TestDiscoveredTuner(TrackingTunerController controller)
        {
            mController = controller;
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
                mTuner = new AllocationTestTuner(mController, this);

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

    private static class AllocationTestTuner extends Tuner
    {
        private AllocationTestTuner(TrackingTunerController controller, ITunerErrorListener tunerErrorListener)
        {
            super(controller, tunerErrorListener, new PolyphaseChannelSourceManager(controller));
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
}
