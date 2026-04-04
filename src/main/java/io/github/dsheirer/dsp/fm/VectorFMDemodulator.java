/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.dsp.fm;

/**
 * FM demodulator that uses JDK 17+ SIMD vector intrinsics
 */
public abstract class VectorFMDemodulator implements IDemodulator
{
    private ScalarFMDemodulator mScalar;

    protected VectorFMDemodulator()
    {
    }

    /**
     * Access the scalar implementation when the sample buffer size is not a multiple of the vector size.
     */
    protected ScalarFMDemodulator getScalarImplementation()
    {
        if(mScalar == null)
        {
            mScalar = new ScalarFMDemodulator();
        }

        return mScalar;
    }
}
