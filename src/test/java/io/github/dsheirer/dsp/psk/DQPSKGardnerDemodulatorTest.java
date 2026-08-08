/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.dsp.psk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.dsp.psk.pll.CostasLoop;
import io.github.dsheirer.dsp.symbol.Dibit;
import io.github.dsheirer.sample.complex.Complex;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DQPSKGardnerDemodulatorTest
{
    private static final float TOLERANCE = 0.000_001f;

    @Test
    void publishesMeasuredDifferentialPhaseAlongsideHardDecisions()
    {
        FixedSampleBuffer buffer = new FixedSampleBuffer();
        DQPSKGardnerDemodulator demodulator = new DQPSKGardnerDemodulator(
            new CostasLoop(48_000, 6_000), buffer);
        List<Float> softSymbols = new ArrayList<>();
        List<Dibit> hardSymbols = new ArrayList<>();
        demodulator.setSoftSymbolListener(softSymbols::add);
        demodulator.setSymbolListener(hardSymbols::add);

        buffer.setSamples(Complex.fromAngle(0.0), Complex.fromAngle(0.0));
        demodulator.calculateSymbol();
        buffer.setSamples(Complex.fromAngle(Math.PI / 4.0), Complex.fromAngle(Math.PI / 4.0));
        demodulator.calculateSymbol();
        buffer.setSamples(Complex.fromAngle(-Math.PI / 2.0), Complex.fromAngle(-Math.PI / 2.0));
        demodulator.calculateSymbol();

        assertEquals(3, hardSymbols.size());
        assertEquals(0.0f, softSymbols.get(0), TOLERANCE);
        assertEquals((float)(Math.PI / 4.0), softSymbols.get(1), TOLERANCE);
        assertEquals((float)(-3.0 * Math.PI / 4.0), softSymbols.get(2), TOLERANCE);
        assertTrue(softSymbols.stream().allMatch(symbol -> symbol >= -Math.PI && symbol <= Math.PI));

        demodulator.setSoftSymbolListener(null);
        buffer.setSamples(Complex.fromAngle(-Math.PI / 4.0), Complex.fromAngle(-Math.PI / 4.0));
        demodulator.calculateSymbol();
        assertEquals(3, softSymbols.size());
        assertEquals(4, hardSymbols.size());
    }

    private static class FixedSampleBuffer extends InterpolatingSampleBuffer
    {
        private final Complex mMiddleSample = new Complex();
        private final Complex mCurrentSample = new Complex();

        private FixedSampleBuffer()
        {
            super(8.0f, 0.1f);
        }

        private void setSamples(Complex middleSample, Complex currentSample)
        {
            mMiddleSample.setValues(middleSample);
            mCurrentSample.setValues(currentSample);
        }

        @Override
        public Complex getCurrentSample()
        {
            return mMiddleSample;
        }

        @Override
        public Complex getMiddleSample()
        {
            return mCurrentSample;
        }

        @Override
        public void resetAndAdjust(float symbolTimingError)
        {
        }
    }
}
