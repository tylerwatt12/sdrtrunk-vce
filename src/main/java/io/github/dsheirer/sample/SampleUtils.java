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

package io.github.dsheirer.sample;

import io.github.dsheirer.sample.complex.ComplexSamples;

public class SampleUtils
{
	private SampleUtils()
	{
	}

	/**
	 * Converts from an interleaved complex sample array to a sample record with the
	 * I and Q in separate arrays.
	 * @param samples that are interleaved complex samples
	 * @param timestamp of the samples
	 * @return deinterleaved complex samples instance
	 */
	public static ComplexSamples deinterleave(float[] samples, long timestamp)
	{
		float[] i = new float[samples.length / 2];
		float[] q = new float[samples.length / 2];

		int offset;

		for(int x = 0; x < i.length; x++)
		{
			offset = 2 * x;
			i[x] = samples[offset];
			q[x] = samples[offset + 1];
		}

		return new ComplexSamples(i, q, timestamp);
	}

	/**
	 * Converts a complex samples buffer to an array of interleaved (I, Q, etc.) samples
	 * @param complexSamples to interleave
	 * @return interleaved samples array
	 */
	public static float[] interleave(ComplexSamples complexSamples)
	{
		float[] i = complexSamples.i();
		float[] q = complexSamples.q();

		float[] complex = new float[i.length + q.length];

		for(int x = 0; x < i.length; x++)
		{
			complex[2 * x] = i[x];
			complex[2 * x + 1] = q[x];
		}

		return complex;
	}

}
