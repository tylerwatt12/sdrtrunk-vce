/*
 * ****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.halfband.RealHalfBandDecimationFilter;
import io.github.dsheirer.dsp.mixer.ScalarComplexMixer;
import io.github.dsheirer.dsp.window.WindowType;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.spectrum.converter.ComplexDecibelConverter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DiagnosticZoomDspTest
{
    private static final int OUTPUT_SAMPLES = 256;
    private static final int SETTLING_SAMPLES = 64;

    @Test
    void matchesTheExistingDecimatorAcrossConsecutiveD2ThroughD32Blocks()
    {
        Random random = new Random(0x5D_2026L);

        for(int decimation = 2; decimation <= 32; decimation *= 2)
        {
            int sourceSamples = (OUTPUT_SAMPLES + SETTLING_SAMPLES) * decimation;
            double mixerFrequency = 137_500.0;
            DiagnosticZoomDsp dsp = new DiagnosticZoomDsp();
            dsp.configure(sourceSamples, decimation, mixerFrequency, 10_000_000.0);
            ScalarComplexMixer referenceMixer = new ScalarComplexMixer(mixerFrequency, 10_000_000.0, true);
            ReferenceDecimator referenceI = new ReferenceDecimator(decimation);
            ReferenceDecimator referenceQ = new ReferenceDecimator(decimation);

            for(int block = 0; block < 3; block++)
            {
                float[] interleaved = new float[sourceSamples * 2];
                float[] inputI = new float[sourceSamples];
                float[] inputQ = new float[sourceSamples];

                for(int sample = 0, offset = 0; sample < sourceSamples; sample++, offset += 2)
                {
                    inputI[sample] = random.nextFloat() * 2.0f - 1.0f;
                    inputQ[sample] = random.nextFloat() * 2.0f - 1.0f;
                    interleaved[offset] = inputI[sample];
                    interleaved[offset + 1] = inputQ[sample];
                }

                ComplexSamples mixed = referenceMixer.mix(inputI, inputQ, block);
                float[] expectedI = referenceI.decimate(mixed.i());
                float[] expectedQ = referenceQ.decimate(mixed.q());
                float[] actual = new float[OUTPUT_SAMPLES * 2];
                dsp.process(interleaved, sourceSamples, actual);
                int start = expectedI.length - OUTPUT_SAMPLES;

                for(int sample = 0, offset = 0; sample < OUTPUT_SAMPLES; sample++, offset += 2)
                {
                    assertEquals(expectedI[start + sample], actual[offset], 2.0e-5f,
                        "I mismatch for D" + decimation + " block " + block + " sample " + sample);
                    assertEquals(expectedQ[start + sample], actual[offset + 1], 2.0e-5f,
                        "Q mismatch for D" + decimation + " block " + block + " sample " + sample);
                }
            }
        }
    }

    @Test
    void steadyStateAndSameSizedPanDoNotAllocateNewWorkspaces()
    {
        int decimation = 32;
        int sourceSamples = (OUTPUT_SAMPLES + SETTLING_SAMPLES) * decimation;
        DiagnosticZoomDsp dsp = new DiagnosticZoomDsp();
        float[] source = new float[sourceSamples * 2];
        float[] destination = new float[OUTPUT_SAMPLES * 2];
        dsp.configure(sourceSamples, decimation, 125_000.0, 10_000_000.0);
        long initialized = dsp.workspaceAllocationCount();

        for(int frame = 0; frame < 20; frame++)
        {
            dsp.process(source, sourceSamples, destination);
        }

        assertEquals(initialized, dsp.workspaceAllocationCount(),
            "steady-state zoom frames must reuse every mixer/decimator workspace");
        dsp.configure(sourceSamples, decimation, -250_000.0, 10_000_000.0);
        assertEquals(initialized, dsp.workspaceAllocationCount(),
            "a same-sized pan must reset state without allocating replacement workspaces");
    }

    @Test
    void retainedDecibelConversionMatchesTheExistingConverter()
    {
        float[] fft = new float[2_048];
        Random random = new Random(42);

        for(int x = 0; x < fft.length; x++)
        {
            fft[x] = random.nextFloat() * 4.0f - 2.0f;
        }

        float[] expected = ComplexDecibelConverter.convert(fft);
        float[] actual = new float[expected.length];
        DiagnosticZoomDsp.decibels(fft, actual);
        assertArrayEquals(expected, actual, 0.0f);
        assertTrue(actual[0] > -196.0f);
    }

    /** Scalar equivalent of the established D2-D32 half-band cascade, retained as an independent test oracle. */
    private static final class ReferenceDecimator
    {
        private final List<IRealDecimationFilter> mStages = new ArrayList<>();

        private ReferenceDecimator(int decimation)
        {
            if(decimation >= 32)
            {
                add(11, WindowType.BLACKMAN);
            }

            if(decimation >= 16)
            {
                add(15, WindowType.BLACKMAN);
            }

            if(decimation >= 8)
            {
                add(15, WindowType.BLACKMAN);
            }

            if(decimation >= 4)
            {
                add(23, WindowType.BLACKMAN);
            }

            add(63, WindowType.HAMMING);
        }

        private void add(int length, WindowType windowType)
        {
            mStages.add(new RealHalfBandDecimationFilter(FilterFactory.getHalfBand(length, windowType)));
        }

        private float[] decimate(float[] samples)
        {
            float[] output = samples;

            for(IRealDecimationFilter stage: mStages)
            {
                output = stage.decimateReal(output);
            }

            return output;
        }
    }
}
