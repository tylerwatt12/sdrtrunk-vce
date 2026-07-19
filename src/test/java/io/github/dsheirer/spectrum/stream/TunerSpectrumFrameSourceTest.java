/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.spectrum.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.test.TestTuner;
import io.github.dsheirer.spectrum.ComplexDftProcessor;
import io.github.dsheirer.spectrum.DFTSize;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TunerSpectrumFrameSourceTest
{
    @Test
    void tapsAnAlreadyRunningTunerAndReleasesEveryListenerAndExecutor() throws Exception
    {
        TestTuner tuner = new TestTuner(null);
        TunerSpectrumFrameSource source = new TunerSpectrumFrameSource(
            new TunerSpectrumFrameSource.Configuration(DFTSize.FFT00512, 20), () -> List.of(tuner));
        ArrayBlockingQueue<SpectrumFrame> frames = new ArrayBlockingQueue<>(1);

        source.start(frames::offer);
        SpectrumFrame frame = frames.poll(3, TimeUnit.SECONDS);

        assertTrue(frame != null);
        assertTrue(source.isRunning());
        assertEquals(tuner.getTunerController().getFrequency(), frame.getCenterFrequencyHz());
        assertEquals(Math.round(tuner.getTunerController().getSampleRate()), frame.getSampleRateHz());
        assertEquals(DFTSize.FFT00512.getSize(), frame.getBinCount());
        assertEquals(SpectrumFrame.FLAG_CAPTURE_TIMESTAMP_VALID,
            frame.getFlags() & SpectrumFrame.FLAG_CAPTURE_TIMESTAMP_VALID);

        source.stop();
        assertFalse(source.isRunning());
        long publishedAtStop = source.getPublishedFrameCount();
        Thread.sleep(100);
        assertEquals(publishedAtStop, source.getPublishedFrameCount());
        source.close();
    }

    @Test
    void complexDftDisposeTerminatesItsOwnedExecutor()
    {
        ComplexDftProcessor processor = new ComplexDftProcessor();
        assertFalse(processor.isExecutorTerminated());
        processor.dispose();
        assertTrue(processor.isExecutorTerminated());
        processor.dispose();
    }

    @Test
    void selectsTunerByNonIdentifyingClassAndFailsClosedWhenUnavailable() throws Exception
    {
        String originalPreferred = System.getProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY);
        String originalClass = System.getProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY);
        ClassedTestTuner rtl = new ClassedTestTuner(TunerClass.RTL2832, "RTL test fixture");
        ClassedTestTuner airspy = new ClassedTestTuner(TunerClass.AIRSPY, "Airspy test fixture");

        try
        {
            System.clearProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY);
            System.setProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY, "AIRSPY");

            try(TunerSpectrumFrameSource source = new TunerSpectrumFrameSource(
                new TunerSpectrumFrameSource.Configuration(DFTSize.FFT00512, 20), () -> List.of(rtl, airspy)))
            {
                ArrayBlockingQueue<SpectrumFrame> frames = new ArrayBlockingQueue<>(1);
                source.start(frames::offer);
                assertTrue(frames.poll(3, TimeUnit.SECONDS) != null);
                assertEquals("Airspy", source.getTargetLabel());
            }

            System.setProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY, "HYDRASDR");

            try(TunerSpectrumFrameSource source = new TunerSpectrumFrameSource(
                new TunerSpectrumFrameSource.Configuration(DFTSize.FFT00512, 20), () -> List.of(rtl, airspy)))
            {
                assertThrows(IllegalStateException.class, () -> source.start(frame -> {}));
                assertFalse(source.isRunning());
            }
        }
        finally
        {
            restoreProperty(TunerSpectrumFrameSource.PREFERRED_TUNER_PROPERTY, originalPreferred);
            restoreProperty(TunerSpectrumFrameSource.TUNER_CLASS_PROPERTY, originalClass);
        }
    }

    private static void restoreProperty(String name, String value)
    {
        if(value == null)
        {
            System.clearProperty(name);
        }
        else
        {
            System.setProperty(name, value);
        }
    }

    private static class ClassedTestTuner extends TestTuner
    {
        private final TunerClass mTunerClass;
        private final String mPreferredName;

        private ClassedTestTuner(TunerClass tunerClass, String preferredName)
        {
            super(null);
            mTunerClass = tunerClass;
            mPreferredName = preferredName;
        }

        @Override
        public TunerClass getTunerClass()
        {
            return mTunerClass;
        }

        @Override
        public String getPreferredName()
        {
            return mPreferredName;
        }
    }
}
