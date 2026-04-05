/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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

package io.github.dsheirer.dsp.filter.dc;

import java.util.Random;

public class ScalarDcRemovalFilter implements IDcRemovalFilter
{
    private float mAverage;
    private float mGain;

    public ScalarDcRemovalFilter(float gain)
    {
        mGain = gain;
    }

    @Override
    public float[] filter(float[] samples)
    {
        float accumulator = 0.0f;
        float average = mAverage;

        for(float sample: samples)
        {
            accumulator += sample;
        }

        float averageDCNow = (accumulator / samples.length) - average;

        average += (mGain * averageDCNow);

        for(int x = 0; x < samples.length; x++)
        {
            samples[x] -= average;
        }

        mAverage = average;

        return samples;
    }
}
