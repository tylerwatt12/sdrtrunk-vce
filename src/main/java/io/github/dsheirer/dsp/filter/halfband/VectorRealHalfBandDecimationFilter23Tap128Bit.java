/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.dsp.filter.halfband;

import jdk.incubator.vector.FloatVector;

/** 23-tap real half-band filter using 128-bit SIMD vectors. */
public class VectorRealHalfBandDecimationFilter23Tap128Bit extends VectorRealHalfBandDecimationFilter
{
    public VectorRealHalfBandDecimationFilter23Tap128Bit(float[] coefficients)
    {
        super(coefficients, FloatVector.SPECIES_128, 23);
    }
}
