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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.test.TestTunerController;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TunerManagerFrequencyPersistenceTest
{
    private static final ChannelSpecification CHANNEL_SPECIFICATION =
        new ChannelSpecification(50_000, 12_500, 6_250, 7_000);

    @Test
    void currentCenterAllocationDoesNotQueueFrequencyPersistence() throws Exception
    {
        TrackingConfigurationManager configurationManager = new TrackingConfigurationManager();
        TrackingTunerController controller = createController(857_000_000L);
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner("current-center", controller);
        TunerManager tunerManager = new TunerManager(null, configurationManager);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        Source source = null;

        try
        {
            source = tunerManager.getSource(new TunerChannel(859_000_000L, 12_500), CHANNEL_SPECIFICATION,
                null, "current-center-no-persistence");

            assertNotNull(source);
            assertEquals(857_000_000L, controller.getFrequency());
            assertTrue(configurationManager.getUpdates().isEmpty());
        }
        finally
        {
            stop(source);
            discoveredTuner.stop();
        }
    }

    @Test
    void successfulRetuneQueuesActualCenterFrequency() throws Exception
    {
        TrackingConfigurationManager configurationManager = new TrackingConfigurationManager();
        TrackingTunerController controller = createController(853_000_000L);
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner("retuned-tuner", controller);
        TunerManager tunerManager = new TunerManager(null, configurationManager);
        tunerManager.getDiscoveredTunerModel().addDiscoveredTuner(discoveredTuner);
        Source source = null;

        try
        {
            source = tunerManager.getSource(new TunerChannel(859_000_000L, 12_500), CHANNEL_SPECIFICATION,
                null, "retune-persistence");

            assertNotNull(source);
            assertNotEquals(853_000_000L, controller.getFrequency());
            assertEquals(List.of(new FrequencyUpdate(TunerType.AIRSPY_R820T, "retuned-tuner",
                controller.getFrequency())), configurationManager.getUpdates());
        }
        finally
        {
            stop(source);
            discoveredTuner.stop();
        }
    }

    private static TrackingTunerController createController(long centerFrequency) throws SourceException
    {
        TrackingTunerController controller = new TrackingTunerController();
        controller.setSampleRate(10_000_000);
        controller.setUsableBandwidthPercentage(0.90);
        controller.setMinimumFrequency(850_000_000L);
        controller.setMaximumFrequency(870_000_000L);
        controller.setFrequency(centerFrequency);
        return controller;
    }

    private static void stop(Source source)
    {
        if(source != null)
        {
            source.stop();
        }
    }

    private static class TrackingConfigurationManager extends TunerConfigurationManager
    {
        private final List<FrequencyUpdate> mUpdates = new ArrayList<>();

        @Override
        public void updateTunerFrequency(TunerType tunerType, String uniqueID, long frequency)
        {
            mUpdates.add(new FrequencyUpdate(tunerType, uniqueID, frequency));
        }

        private List<FrequencyUpdate> getUpdates()
        {
            return List.copyOf(mUpdates);
        }
    }

    private record FrequencyUpdate(TunerType tunerType, String uniqueID, long frequency)
    {
    }

    private static class TrackingTunerController extends TestTunerController
    {
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
    }

    private static class TestDiscoveredTuner extends DiscoveredTuner
    {
        private final String mId;
        private final TrackingTunerController mController;

        private TestDiscoveredTuner(String id, TrackingTunerController controller)
        {
            mId = id;
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
            return mId;
        }

        @Override
        public void start()
        {
            if(!hasTuner())
            {
                PolyphaseChannelSourceManager sourceManager = new PolyphaseChannelSourceManager(mController);
                mTuner = new TestTuner(mController, this, sourceManager, mId);

                try
                {
                    mTuner.start();
                }
                catch(SourceException e)
                {
                    throw new IllegalStateException("Unable to start tuner persistence test tuner", e);
                }
            }
        }
    }

    private static class TestTuner extends Tuner
    {
        private final String mId;

        private TestTuner(TrackingTunerController controller, ITunerErrorListener errorListener,
                          ChannelSourceManager sourceManager, String id)
        {
            super(controller, errorListener, sourceManager);
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
            return TunerType.AIRSPY_R820T;
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
}
