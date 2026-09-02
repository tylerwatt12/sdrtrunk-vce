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
import io.github.dsheirer.dsp.filter.halfband.VectorRealHalfBandDecimationFilter128Bit;
import io.github.dsheirer.dsp.filter.halfband.VectorRealHalfBandDecimationFilter256Bit;
import io.github.dsheirer.dsp.filter.halfband.VectorRealHalfBandDecimationFilter512Bit;
import io.github.dsheirer.dsp.filter.halfband.VectorRealHalfBandDecimationFilter64Bit;
import io.github.dsheirer.dsp.filter.halfband.VectorRealHalfBandDecimationFilterDefaultBit;
import io.github.dsheirer.vector.calibrate.CalibrationType;
import io.github.dsheirer.vector.calibrate.Implementation;

/**
 * Selects the fastest correct generic real half-band decimation filter for the current CPU.
 *
 * <p>The 47-tap fixture deliberately avoids the four specialized production classes so this calibration measures
 * the generic default implementations it controls.  Forty-seven is the nearest valid half-band length to 49 because
 * half-band coefficient counts must satisfy {@code N = 4m + 3}.</p>
 */
public class RealHalfBandDefaultFilterCalibration extends AbstractRealHalfBandFilterCalibration
{
    private static final int GENERIC_TAP_COUNT = 47;

    public RealHalfBandDefaultFilterCalibration()
    {
        super(CalibrationType.FILTER_HALF_BAND_REAL_DEFAULT, GENERIC_TAP_COUNT, true);
    }

    @Override
    protected IRealDecimationFilter createFilter(Implementation implementation, float[] coefficients)
    {
        return switch(implementation)
        {
            case SCALAR -> new RealHalfBandDecimationFilter(coefficients);
            case VECTOR_SIMD_PREFERRED -> new VectorRealHalfBandDecimationFilterDefaultBit(coefficients);
            case VECTOR_SIMD_64 -> new VectorRealHalfBandDecimationFilter64Bit(coefficients);
            case VECTOR_SIMD_128 -> new VectorRealHalfBandDecimationFilter128Bit(coefficients);
            case VECTOR_SIMD_256 -> new VectorRealHalfBandDecimationFilter256Bit(coefficients);
            case VECTOR_SIMD_512 -> new VectorRealHalfBandDecimationFilter512Bit(coefficients);
            default -> throw new IllegalArgumentException("Unsupported generic implementation: " + implementation);
        };
    }
}
