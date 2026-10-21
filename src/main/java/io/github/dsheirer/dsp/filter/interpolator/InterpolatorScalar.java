/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.dsp.filter.interpolator;

import io.github.dsheirer.sample.complex.Complex;
import org.apache.commons.lang3.Validate;

public class InterpolatorScalar extends Interpolator
{
    private float mGain;

    /**
     * Provides an interpolated sample point along an eight-sample waveform representation using 128 filters to
     * provide approximate interpolation with a resolution of 1/128th of a sample.
     *
     * @param gain to apply to the interpolated sample
     */
    public InterpolatorScalar(float gain)
    {
        mGain = gain;
    }

    public InterpolatorScalar()
    {
        this(1.0f);
    }

    /**
     * Calculates an interpolated value from eight samples that start at the offset into the sample array.  The
     * interpolated sample will fall within the middle of the eight sample array, between indexes offset+3 and
     * offset+4.  The mu argument is translated into an index position between 0 and 128, where a mu of 0.0 will be
     * converted to index zero and will be equal to the sample at index offset+3 and a mu of 1.0 will be equal to
     * the sample at offset+4.  All mu values between 0.0 and 1.0 will be converted to a 1 - 127 index and will
     * produce an approximated value from among 127 interpolated sample points between indexes offset+3 and offset+4.
     *
     * @param samples - sample array of length at least offset + 7
     * @param mu - interpolated sample position between 0.0 and 1.0
     * @return - interpolated sample value
     */
    public float filter(float[] samples, int offset, float mu)
    {
        /* Ensure we have enough samples in the array */
        Validate.isTrue(samples.length >= offset + NTAPS);

        /* Identify the filter bank that corresponds to mu */
        int index = (int)(NSTEPS * mu);

        float accumulator = (TAPS[index][7] * samples[offset]);
        accumulator += (TAPS[index][6] * samples[offset + 1]);
        accumulator += (TAPS[index][5] * samples[offset + 2]);
        accumulator += (TAPS[index][4] * samples[offset + 3]);
        accumulator += (TAPS[index][3] * samples[offset + 4]);
        accumulator += (TAPS[index][2] * samples[offset + 5]);
        accumulator += (TAPS[index][1] * samples[offset + 6]);
        accumulator += (TAPS[index][0] * samples[offset + 7]);

        return accumulator * mGain;
    }

    public Complex filter(float[] iSamples, float[] qSamples, int offset, float mu)
    {
        float i = filter(iSamples, offset, mu);
        float q = filter(qSamples, offset, mu);

        return new Complex(i, q);
    }

}
