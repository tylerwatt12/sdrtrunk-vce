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

import io.github.dsheirer.bits.BinaryMessage;

/**
 * Converts P25 Phase 2 72-bit AMBE voice frames to/from the 49 protected voice bits encrypted by P25 algorithms.
 */
public class P25Phase2AmbeAdapter
{
    private static final AmbeProtectedVoiceAdapter.Interleave INTERLEAVE =
        new AmbeProtectedVoiceAdapter.Interleave("P25 Phase 2 AMBE",
            new int[] {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 64, 68,
                1, 5, 9, 13, 17, 21},
            new int[] {25, 29, 33, 37, 41, 45, 49, 53, 57, 61, 65, 69, 2, 6, 10, 14, 18, 22,
                26, 30, 34, 38, 42},
            new int[] {46, 50, 54, 58, 62, 66, 70, 3, 7, 11, 15},
            new int[] {19, 23, 27, 31, 35, 39, 43, 47, 51, 55, 59, 63, 67, 71});

    private P25Phase2AmbeAdapter()
    {
    }

    public static byte[] decode9ToPacked7(byte[] frame) throws VoiceFrameDecryptionException
    {
        byte[] packed = AmbeProtectedVoiceAdapter.packVoiceBits(
            AmbeProtectedVoiceAdapter.decode9ToVoice49(frame, INTERLEAVE));
        maskUnusedVoiceBits(packed);
        return packed;
    }

    public static byte[] encodePacked7To9(byte[] packed) throws VoiceFrameDecryptionException
    {
        if(packed == null || packed.length != AmbeProtectedVoiceAdapter.PACKED_VOICE_BYTES)
        {
            throw new VoiceFrameDecryptionException("P25 Phase 2 AMBE requires 7 packed voice bytes");
        }

        byte[] sanitized = packed.clone();
        maskUnusedVoiceBits(sanitized);
        return AmbeProtectedVoiceAdapter.encodeVoice49To9(
            AmbeProtectedVoiceAdapter.packVoiceBytesToBits(sanitized), INTERLEAVE);
    }

    public static BinaryMessage encodePacked7ToBinaryMessage(byte[] packed) throws VoiceFrameDecryptionException
    {
        return BinaryMessage.from(encodePacked7To9(packed));
    }

    public static void maskUnusedVoiceBits(byte[] packed)
    {
        packed[6] &= (byte)0x80;
    }
}
