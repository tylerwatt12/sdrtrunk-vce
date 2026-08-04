/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase2.enumeration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DataUnitIDTest
{
    @Test
    void exhaustivelyDecodesOnlyExactAndSingleBitValues()
    {
        int exactCount = 0;
        int correctedCount = 0;
        int rejectedCount = 0;

        for(int value = 0; value <= 0xFF; value++)
        {
            int minimumDistance = 8;
            int nearestCount = 0;
            DataUnitID nearest = DataUnitID.UNKNOWN;

            for(DataUnitID duid: DataUnitID.VALID_VALUES)
            {
                int distance = Integer.bitCount(value ^ duid.getValueWithParity());

                if(distance < minimumDistance)
                {
                    minimumDistance = distance;
                    nearestCount = 1;
                    nearest = duid;
                }
                else if(distance == minimumDistance)
                {
                    nearestCount++;
                }
            }

            if(minimumDistance == 0)
            {
                assertEquals(1, nearestCount);
                assertEquals(nearest, DataUnitID.fromEncodedValue(value));
                exactCount++;
            }
            else if(minimumDistance == 1)
            {
                assertEquals(1, nearestCount);
                assertEquals(nearest, DataUnitID.fromEncodedValue(value));
                correctedCount++;
            }
            else
            {
                assertEquals(2, minimumDistance);
                assertTrue(nearestCount > 1, "Every two-bit value must be ambiguous");
                assertEquals(DataUnitID.UNKNOWN, DataUnitID.fromEncodedValue(value));
                rejectedCount++;
            }
        }

        assertEquals(16, exactCount);
        assertEquals(128, correctedCount);
        assertEquals(112, rejectedCount);
    }
}
