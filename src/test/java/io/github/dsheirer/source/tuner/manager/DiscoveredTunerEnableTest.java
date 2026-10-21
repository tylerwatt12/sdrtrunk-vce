/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.test.TestTunerController;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DiscoveredTunerEnableTest
{
    @Test
    void discoveryIsAnExplicitStartConfigurePublishCommandAndStatusObserversDoNotStartHardware()
    {
        TrackingController controller = new TrackingController(false);
        TestDiscoveredTuner discovered = new TestDiscoveredTuner(controller, false);
        AirspyTunerConfiguration configuration = configuration();
        AtomicBoolean publishedReady = new AtomicBoolean();
        discovered.configureOnStart(tuner -> configuration);
        discovered.addTunerStatusListener((tuner, previous, current) -> {
            if(current == TunerStatus.ENABLED)
                publishedReady.set(controller.mConfigurationApplied && controller.mApplyCount == 1);
        });

        assertTrue(discovered.startConfigured());
        assertTrue(publishedReady.get());
        assertEquals(configuration, discovered.getTunerConfiguration());
        assertEquals(1, discovered.mStartCount);

        TunerConfigurationManager observer = new TunerConfigurationManager()
        {
            @Override public void tunerStatusUpdated(DiscoveredTuner tuner, TunerStatus previous, TunerStatus current) {}
        };
        TunerManager manager = new TunerManager(null, observer);
        manager.tunerStatusUpdated(discovered, TunerStatus.DISABLED, TunerStatus.ENABLED);
        assertEquals(1, discovered.mStartCount, "a notification cannot command a second hardware startup");
        assertEquals(1, controller.mApplyCount);

        discovered.setTunerConfiguration(configuration());
        assertEquals(1, controller.mApplyCount, "assigning saved values is not an implicit hardware command");
        discovered.stop();
    }

    @Test
    void failedInitialConfigurationNeverPublishesEnabled()
    {
        TrackingController controller = new TrackingController(true);
        TestDiscoveredTuner discovered = new TestDiscoveredTuner(controller, false);
        discovered.configureOnStart(tuner -> configuration());
        AtomicBoolean enabled = new AtomicBoolean();
        discovered.addTunerStatusListener((tuner, previous, current) -> {
            if(current == TunerStatus.ENABLED) enabled.set(true);
        });
        assertFalse(discovered.startConfigured());
        assertFalse(enabled.get());
        assertFalse(discovered.isEnabled());
        assertFalse(discovered.isAvailableForAllocation());
        assertFalse(discovered.hasTuner());
        assertEquals(1, controller.mApplyCount);
    }

    @Test
    void restoresConfigurationBeforeAdvertisingTunerAsAvailable()
    {
        TrackingController controller = new TrackingController(false);
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(controller);
        AirspyTunerConfiguration configuration = configuration();
        discoveredTuner.setTunerConfiguration(configuration);
        AtomicBoolean listenerObservedAppliedConfiguration = new AtomicBoolean();
        discoveredTuner.addTunerStatusListener((tuner, previous, current) -> {
            if(current == TunerStatus.ENABLED)
            {
                listenerObservedAppliedConfiguration.set(controller.mConfigurationApplied &&
                        controller.getMinimumFrequency() == configuration.getMinimumFrequency() &&
                        controller.getMaximumFrequency() == configuration.getMaximumFrequency());
            }
        });

        assertFalse(discoveredTuner.isAvailable());
        discoveredTuner.setEnabled(true);

        assertTrue(discoveredTuner.isAvailable());
        assertTrue(listenerObservedAppliedConfiguration.get());
        assertEquals(configuration.getFrequency(), controller.getFrequency());
        assertTrue(controller.isCenterFrequencyLocked());
        assertEquals(1, controller.mApplyCount);
        discoveredTuner.stop();
    }

    @Test
    void failedConfigurationRestoreDoesNotExposeTunerToAllocation()
    {
        TrackingController controller = new TrackingController(true);
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(controller);
        discoveredTuner.setTunerConfiguration(configuration());

        discoveredTuner.setEnabled(true);

        assertFalse(discoveredTuner.isAvailable());
        assertEquals(TunerStatus.ERROR, discoveredTuner.getTunerStatus());
        assertFalse(discoveredTuner.hasTuner());

        discoveredTuner.setEnabled(false);
        assertEquals(TunerStatus.DISABLED, discoveredTuner.getTunerStatus());
        assertEquals(DiscoveredTuner.OperatorState.DISABLED, discoveredTuner.getOperatorState());
    }

    private static AirspyTunerConfiguration configuration()
    {
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration("enable-sequence");
        configuration.setFrequency(855_000_000L);
        configuration.setMinimumFrequency(850_000_000L);
        configuration.setMaximumFrequency(860_000_000L);
        configuration.setCenterFrequencyLocked(true);
        return configuration;
    }

    private static class TrackingController extends TestTunerController
    {
        private final boolean mFailApply;
        private boolean mConfigurationApplied;
        private int mApplyCount;

        private TrackingController(boolean failApply)
        {
            mFailApply = failApply;
        }

        @Override
        public void apply(TunerConfiguration configuration) throws SourceException
        {
            mApplyCount++;

            if(mFailApply)
            {
                throw new SourceException("expected test failure");
            }

            setMinimumFrequency(configuration.getMinimumFrequency());
            setMaximumFrequency(configuration.getMaximumFrequency());
            setFrequency(configuration.getFrequency());
            setCenterFrequencyLocked(configuration.isCenterFrequencyLocked());
            mConfigurationApplied = true;
        }

        @Override
        public void addBufferListener(Listener<INativeBuffer> listener)
        {
            //No sample stream is needed for this lifecycle test.
        }

        @Override
        public void removeBufferListener(Listener<INativeBuffer> listener)
        {
            //No sample stream is used for this lifecycle test.
        }
    }

    private static class TestDiscoveredTuner extends DiscoveredTuner
    {
        private final TrackingController mController;
        private int mStartCount;

        private TestDiscoveredTuner(TrackingController controller)
        {
            this(controller, true);
        }

        private TestDiscoveredTuner(TrackingController controller, boolean disabled)
        {
            mController = controller;
            if(disabled) setEnabled(false);
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.TEST_TUNER;
        }

        @Override
        public String getId()
        {
            return "enable-sequence";
        }

        @Override
        public void start()
        {
            mStartCount++;
            if(isEnabled() && !hasTuner())
            {
                mTuner = new TestTuner(mController, this);

                try
                {
                    mTuner.start();
                }
                catch(SourceException se)
                {
                    throw new IllegalStateException(se);
                }
            }
        }
    }

    private static class TestTuner extends Tuner
    {
        private TestTuner(TrackingController controller, ITunerErrorListener errorListener)
        {
            super(controller, errorListener, new PolyphaseChannelSourceManager(controller));
        }

        @Override
        public int getMaximumUSBBitsPerSecond()
        {
            return 0;
        }

        @Override
        public String getUniqueID()
        {
            return "enable-sequence";
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.TEST_TUNER;
        }

        @Override
        public String getPreferredName()
        {
            return "Enable Sequence Test Tuner";
        }

        @Override
        public double getSampleSize()
        {
            return 16.0;
        }
    }
}
