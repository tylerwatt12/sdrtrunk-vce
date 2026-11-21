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

package io.github.dsheirer.audio.codec.mbe.decrypt.dmr;

import io.github.dsheirer.audio.codec.mbe.decrypt.AmbeProtectedVoiceAdapter;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptionException;

/**
 * Converts between the 9-byte DMR/JMBE AMBE frame shape and the 49 protected AMBE voice bits used by DMRA privacy.
 */
public class DmrAmbeAdapter
{
    public static final int VOICE_BITS = AmbeProtectedVoiceAdapter.VOICE_BITS;

    private static final AmbeProtectedVoiceAdapter.Interleave INTERLEAVE =
        new AmbeProtectedVoiceAdapter.Interleave("DMR AMBE privacy",
            new int[] {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 64, 68,
                1, 5, 9, 13, 17, 21},
            new int[] {25, 29, 33, 37, 41, 45, 49, 53, 57, 61, 65, 69, 2, 6, 10, 14, 18, 22,
                26, 30, 34, 38, 42},
            new int[] {46, 50, 54, 58, 62, 66, 70, 3, 7, 11, 15},
            new int[] {19, 23, 27, 31, 35, 39, 43, 47, 51, 55, 59, 63, 67, 71});

    private DmrAmbeAdapter()
    {
    }

    /**
     * Decodes a transmitted 9-byte AMBE frame into 49 protected AMBE voice bits.
     */
    public static boolean[] decode9ToVoice49(byte[] frame) throws VoiceFrameDecryptionException
    {
        return AmbeProtectedVoiceAdapter.decode9ToVoice49(frame, INTERLEAVE);
    }

    /**
     * Encodes 49 protected AMBE voice bits into a JMBE-compatible 9-byte coded AMBE frame.
     */
    public static byte[] encodeVoice49To9(boolean[] voice) throws VoiceFrameDecryptionException
    {
        return AmbeProtectedVoiceAdapter.encodeVoice49To9(voice, INTERLEAVE);
    }

    public static boolean[] packVoiceBytesToBits(byte[] packed) throws VoiceFrameDecryptionException
    {
        return AmbeProtectedVoiceAdapter.packVoiceBytesToBits(packed);
    }

    public static byte[] packVoiceBits(boolean[] bits) throws VoiceFrameDecryptionException
    {
        return AmbeProtectedVoiceAdapter.packVoiceBits(bits);
    }
}
