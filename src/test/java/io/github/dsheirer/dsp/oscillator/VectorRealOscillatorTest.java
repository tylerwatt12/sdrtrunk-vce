/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.dsp.oscillator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import jdk.incubator.vector.FloatVector;
import org.junit.jupiter.api.Test;

class VectorRealOscillatorTest
{
    private static final double FREQUENCY = 5_000.0d;
    private static final double SAMPLE_RATE = 50_000.0d;
    private static final float TOLERANCE = 0.000_01f;

    @Test
    void alignedAndTailLengthsMatchScalarAcrossBufferBoundaries()
    {
        IRealOscillator scalar = new ScalarRealOscillator(FREQUENCY, SAMPLE_RATE);
        IRealOscillator vector = new VectorRealOscillator(FREQUENCY, SAMPLE_RATE);
        int lanes = FloatVector.SPECIES_PREFERRED.length();
        int[] lengths = {lanes * 3, lanes + 1, 1, lanes * 2 - 1, lanes * 2};

        for(int length: lengths)
        {
            assertArrayEquals(scalar.generate(length), vector.generate(length), TOLERANCE,
                "stream buffer length " + length);
        }
    }

    @Test
    void frequencyChangePreservesTheLastReturnedSamplePhaseAfterATail()
    {
        IRealOscillator scalar = new ScalarRealOscillator(FREQUENCY, SAMPLE_RATE);
        IRealOscillator vector = new VectorRealOscillator(FREQUENCY, SAMPLE_RATE);
        int tailLength = FloatVector.SPECIES_PREFERRED.length() + 3;
        scalar.generate(tailLength);
        vector.generate(tailLength);
        scalar.setFrequency(7_500.0d);
        vector.setFrequency(7_500.0d);

        assertArrayEquals(scalar.generate(tailLength), vector.generate(tailLength), TOLERANCE);
    }
}
