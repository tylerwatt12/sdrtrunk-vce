/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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
package io.github.dsheirer.spectrum;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WaterfallPanelTest
{
    @Test
    void downsamplesLargestFftWithoutLosingPeakBins()
    {
        byte[] source = new byte[32768];
        source[16384] = (byte)255;
        byte[] destination = new byte[1024];

        WaterfallPanel.renderDisplayRow(source, 0, source.length, 1, 0, destination, 0, destination.length);

        assertEquals(255, Byte.toUnsignedInt(destination[512]));
        assertEquals(1, countNonZero(destination));
    }

    @Test
    void preservesFullFftDetailWhenZoomed()
    {
        byte[] source = new byte[32768];
        source[16400] = (byte)200;
        byte[] destination = new byte[2048];

        WaterfallPanel.renderDisplayRow(source, 0, source.length, 64, 16384, destination, 0, destination.length);

        assertEquals(200, Byte.toUnsignedInt(destination[64]));
        assertEquals(4, countNonZero(destination));
    }

    @Test
    void usesStrongestBinForEachDisplayPixel()
    {
        byte[] source = {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] destination = new byte[4];

        WaterfallPanel.renderDisplayRow(source, 0, source.length, 1, 0, destination, 0, destination.length);

        assertArrayEquals(new byte[]{2, 4, 6, 8}, destination);
    }

    private static int countNonZero(byte[] values)
    {
        int count = 0;

        for(byte value: values)
        {
            if(value != 0)
            {
                count++;
            }
        }

        return count;
    }
}
