/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.dsp.filter.channelizer;

import io.github.dsheirer.vector.calibrate.CalibrationManager;
import io.github.dsheirer.vector.calibrate.CalibrationType;
import io.github.dsheirer.vector.calibrate.Implementation;
import jdk.incubator.vector.FloatVector;

/**
 * Selects the calibrated polyphase channelizer filter implementation.
 */
public final class PolyphaseChannelizerFilterFactory
{
    private static final IPolyphaseChannelizerFilter SCALAR = new ScalarPolyphaseChannelizerFilter();
    private static final IPolyphaseChannelizerFilter VECTOR_64 = VectorPolyphaseChannelizerFilter::filter64;
    private static final IPolyphaseChannelizerFilter VECTOR_128 = VectorPolyphaseChannelizerFilter::filter128;
    private static final IPolyphaseChannelizerFilter VECTOR_256 = VectorPolyphaseChannelizerFilter::filter256;
    private static final IPolyphaseChannelizerFilter VECTOR_512 = VectorPolyphaseChannelizerFilter::filter512;

    private PolyphaseChannelizerFilterFactory()
    {
    }

    /**
     * Returns the implementation selected by this host's calibration.
     */
    public static IPolyphaseChannelizerFilter getFilter()
    {
        Implementation implementation = CalibrationManager.getInstance()
            .getImplementation(CalibrationType.POLYPHASE_CHANNELIZER_FILTER);
        return getFilter(implementation);
    }

    /**
     * Returns the requested implementation.  This overload is also used by calibration and correctness tests.
     */
    public static IPolyphaseChannelizerFilter getFilter(Implementation implementation)
    {
        return switch(implementation)
        {
            case VECTOR_SIMD_64 -> VECTOR_64;
            case VECTOR_SIMD_128 -> VECTOR_128;
            case VECTOR_SIMD_256 -> VECTOR_256;
            case VECTOR_SIMD_512 -> VECTOR_512;
            case VECTOR_SIMD_PREFERRED -> getPreferredVectorFilter();
            case SCALAR, UNCALIBRATED -> SCALAR;
        };
    }

    private static IPolyphaseChannelizerFilter getPreferredVectorFilter()
    {
        return switch(FloatVector.SPECIES_PREFERRED.vectorBitSize())
        {
            case 64 -> VECTOR_64;
            case 128 -> VECTOR_128;
            case 256 -> VECTOR_256;
            case 512 -> VECTOR_512;
            default -> SCALAR;
        };
    }
}
