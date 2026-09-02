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

package io.github.dsheirer.vector.calibrate.filter;

import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.halfband.RealHalfBandDecimationFilter;
import io.github.dsheirer.dsp.filter.halfband.VectorRealHalfBandDecimationFilter63Tap128Bit;
import io.github.dsheirer.dsp.filter.halfband.VectorRealHalfBandDecimationFilter63Tap256Bit;
import io.github.dsheirer.dsp.filter.halfband.VectorRealHalfBandDecimationFilter63Tap512Bit;
import io.github.dsheirer.dsp.filter.halfband.VectorRealHalfBandDecimationFilter63Tap64Bit;
import io.github.dsheirer.vector.calibrate.CalibrationType;
import io.github.dsheirer.vector.calibrate.Implementation;

/** Selects the fastest correct 63-tap real half-band decimation filter for the current CPU. */
public class RealHalfBand63TapFilterCalibration extends AbstractRealHalfBandFilterCalibration
{
    public RealHalfBand63TapFilterCalibration()
    {
        super(CalibrationType.FILTER_HALF_BAND_REAL_63_TAP, 63, false);
    }

    @Override
    protected IRealDecimationFilter createFilter(Implementation implementation, float[] coefficients)
    {
        return switch(implementation)
        {
            case SCALAR -> new RealHalfBandDecimationFilter(coefficients);
            case VECTOR_SIMD_64 -> new VectorRealHalfBandDecimationFilter63Tap64Bit(coefficients);
            case VECTOR_SIMD_128 -> new VectorRealHalfBandDecimationFilter63Tap128Bit(coefficients);
            case VECTOR_SIMD_256 -> new VectorRealHalfBandDecimationFilter63Tap256Bit(coefficients);
            case VECTOR_SIMD_512 -> new VectorRealHalfBandDecimationFilter63Tap512Bit(coefficients);
            default -> throw new IllegalArgumentException("Unsupported 63-tap implementation: " + implementation);
        };
    }
}
