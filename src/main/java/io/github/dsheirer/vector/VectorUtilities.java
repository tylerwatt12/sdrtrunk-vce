/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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

package io.github.dsheirer.vector;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilities for working with Project Panama SIMD vectors in JDK 17+.
 */
public class VectorUtilities
{
    private static final Logger mLog = LoggerFactory.getLogger(VectorUtilities.class);
    private static boolean mSpeciesMismatchLogged = false;

    private VectorUtilities()
    {
    }

    /**
     * Checks the species to determine if it is compatible with the preferred species for the runtime CPU
     * and logs a warning if the species' lane width is wider than the preferred species ... which would be
     * hugely inefficient.
     *
     * @param species to test
     */
    public static void checkSpecies(VectorSpecies<Float> species)
    {
        if(FloatVector.SPECIES_PREFERRED.length() < species.length() && !mSpeciesMismatchLogged)
        {
            mLog.warn("CPU supports maximum SIMD instructions of " + FloatVector.SPECIES_PREFERRED);
            mSpeciesMismatchLogged = true;
        }
    }

    /**
     * Checks the I/Q sample array length to be an integer multiple of the SIMD lane width.
     * @param i samples
     * @param q samples
     * @param species used for SIMD operations
     */
    public static void checkComplexArrayLength(float[] i, float[] q, VectorSpecies<Float> species)
    {
        if(i.length != q.length)
        {
            throw new IllegalArgumentException("I/Q buffer lengths must match. I length [" + i.length +
                "] Q length [" + q.length + "]");
        }

        if(i.length % species.length() != 0)
        {
            throw new IllegalArgumentException("I/Q buffer lengths [" + i.length + "] must be a power of 2 multiple of " +
                    "SIMD lane width [" + species.length() + "]");
        }
    }

}
