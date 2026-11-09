/* Copyright (C) 2026. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.dsp.filter.channelizer;

import java.util.Random;
import org.jtransforms.fft.FloatFFT_1D;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ChannelizerInverseTransformTest
{
    @Test
    void normalizedInverseMatchesJTransformsAcrossRepeatedBatchesAndSupportedSizes()
    {
        //96 and 400 are used by RTL-SDR and Airspy. 64 and 14 exercise the existing FFT paths.
        int[] channelCounts = {12, 14, 64, 96, 400};
        Random random = new Random(0x2568);

        for(int count: channelCounts)
        {
            var inverse = ComplexPolyphaseChannelizerM2.createInverseTransform(count);
            var reference = new FloatFFT_1D(count);

            for(int batch = 0; batch < 8; batch++)
            {
                float[] input = new float[2 * count];

                for(int index = 0; index < input.length; index++)
                {
                    input[index] = random.nextFloat(-1.0f, 1.0f);
                }

                float[] expected = input.clone();
                reference.complexInverse(expected, true);
                inverse.accept(input);
                assertArrayEquals(expected, input, "channel count " + count + ", batch " + batch);
            }
        }
    }
}
