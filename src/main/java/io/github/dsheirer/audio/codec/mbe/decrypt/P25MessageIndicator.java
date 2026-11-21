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

package io.github.dsheirer.audio.codec.mbe.decrypt;

import io.github.dsheirer.audio.codec.mbe.decrypt.crypto.HexByteUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * APCO25 64-bit message indicator helpers.
 */
class P25MessageIndicator
{
    static final int BYTES = 8;

    private P25MessageIndicator()
    {
    }

    static byte[] parse64(String messageIndicator) throws VoiceFrameDecryptionException
    {
        return HexByteUtil.parseFirstBytes(messageIndicator, BYTES, "APCO25 message indicator");
    }

    static byte[] expandTo128(byte[] mi)
    {
        long lfsr = 0;

        for(int x = 0; x < BYTES; x++)
        {
            lfsr = (lfsr << 8) | (mi[x] & 0xFFL);
        }

        long overflow = 0;

        for(int x = 0; x < 64; x++)
        {
            long out = (lfsr >>> 63) & 1L;
            long feedback = ((lfsr >>> 63) ^ (lfsr >>> 61) ^ (lfsr >>> 45) ^
                (lfsr >>> 37) ^ (lfsr >>> 26) ^ (lfsr >>> 14)) & 1L;
            lfsr = (lfsr << 1) | feedback;
            overflow = (overflow << 1) | out;
        }

        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(overflow);
        buffer.putLong(lfsr);
        return buffer.array();
    }
}
