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
package io.github.dsheirer.sample.adapter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Converts 16-bit/2-byte little endian byte data into a reusable buffer of real float sample data
 */
public class RealShortAdapter extends RealSampleAdapter
{
    private ByteOrder mByteOrder = ByteOrder.LITTLE_ENDIAN;

    @Override
    public float[] convert(byte[] samples)
    {
        float[] convertedSamples = new float[samples.length / 2];

        int pointer = 0;

        ByteBuffer byteBuffer = ByteBuffer.wrap(samples);

        /* Set endian to correct byte ordering */
        byteBuffer.order(mByteOrder);

        while(byteBuffer.hasRemaining())
        {
            convertedSamples[pointer] = byteBuffer.getShort() / 32768.0f;
            pointer++;
        }

        return convertedSamples;
    }

    /**
     * Set byte interpretation to little or big endian.  Defaults to LITTLE
     * endian
     */
    public void setByteOrder(ByteOrder order)
    {
        mByteOrder = order;
    }
}
