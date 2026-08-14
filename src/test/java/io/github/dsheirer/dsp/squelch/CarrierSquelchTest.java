/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.squelch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CarrierSquelchTest
{
    private static final double SAMPLE_RATE = 50_000.0;
    private static final int BUFFER_LENGTH = 1_000;

    @Test
    void idleFirstCarrierAndLossProduceOneOrderedCallAtEveryModulationDepth()
    {
        for(float modulationDepth: new float[] {0.0f, 0.5f, 0.95f})
        {
            Scenario scenario = new Scenario();

            scenario.idle(10);

            assertTrue(scenario.mSquelch.isSquelched());
            assertTrue(scenario.mOutput.isEmpty(), "Idle RF must not produce audio or a call");

            scenario.carrier(10, modulationDepth);
            scenario.idle(10);

            scenario.assertOneOrderedCall();
            int countAfterCall = scenario.mOutput.size();
            scenario.idle(100);
            assertEquals(countAfterCall, scenario.mOutput.size(),
                "Extended idle RF must not create segmented false calls at depth " + modulationDepth);
        }
    }

    @Test
    void carrierFirstIsAcquiredWithoutIdleCalibrationAtEveryModulationDepth()
    {
        for(float modulationDepth: new float[] {0.0f, 0.5f, 0.95f})
        {
            Scenario scenario = new Scenario();

            scenario.carrier(10, modulationDepth);

            assertFalse(scenario.mSquelch.isSquelched());
            assertEquals(1, scenario.mOutput.stream().filter("state:UNSQUELCH"::equals).count());
            assertEquals("state:UNSQUELCH", scenario.mOutput.getFirst());

            scenario.idle(10);
            scenario.assertOneOrderedCall();
        }
    }

    private static class Scenario
    {
        private final CarrierSquelch mSquelch = new CarrierSquelch(0.1f, 0.19f, 2, 3);
        private final List<String> mOutput = new ArrayList<>();
        private final Random mRandom = new Random(0x5D47A11L);
        private double mCarrierPhase;
        private double mAudioPhase;

        private Scenario()
        {
            mSquelch.setSampleRate(SAMPLE_RATE);
            mSquelch.setSquelchStateListener(state -> mOutput.add("state:" + state));
            mSquelch.setAudioListener(audio -> mOutput.add("audio"));
        }

        private void carrier(int buffers, float modulationDepth)
        {
            for(int buffer = 0; buffer < buffers; buffer++)
            {
                float[] i = new float[BUFFER_LENGTH];
                float[] q = new float[BUFFER_LENGTH];
                float[] audio = new float[BUFFER_LENGTH];

                for(int x = 0; x < BUFFER_LENGTH; x++)
                {
                    float envelope = 0.20f * (1.0f + modulationDepth * (float)Math.sin(mAudioPhase));
                    i[x] = envelope * (float)Math.cos(mCarrierPhase);
                    q[x] = envelope * (float)Math.sin(mCarrierPhase);
                    audio[x] = envelope;
                    mCarrierPhase += 2.0 * Math.PI * 500.0 / SAMPLE_RATE;
                    mAudioPhase += 2.0 * Math.PI * 1_000.0 / SAMPLE_RATE;
                }

                mSquelch.process(audio, i, q);
            }
        }

        private void idle(int buffers)
        {
            for(int buffer = 0; buffer < buffers; buffer++)
            {
                float[] i = new float[BUFFER_LENGTH];
                float[] q = new float[BUFFER_LENGTH];
                float[] audio = new float[BUFFER_LENGTH];

                for(int x = 0; x < BUFFER_LENGTH; x++)
                {
                    i[x] = (float)(mRandom.nextGaussian() * 0.004);
                    q[x] = (float)(mRandom.nextGaussian() * 0.004);
                    audio[x] = (float)Math.sqrt(i[x] * i[x] + q[x] * q[x]);
                }

                mSquelch.process(audio, i, q);
            }
        }

        private void assertOneOrderedCall()
        {
            assertTrue(mSquelch.isSquelched());
            assertEquals(1, mOutput.stream().filter("state:UNSQUELCH"::equals).count());
            assertEquals(1, mOutput.stream().filter("state:SQUELCH"::equals).count());
            assertEquals("state:UNSQUELCH", mOutput.getFirst(), "Call start must precede its audio");
            assertEquals("state:SQUELCH", mOutput.getLast(), "Closing audio must flush before call end");
        }
    }
}
