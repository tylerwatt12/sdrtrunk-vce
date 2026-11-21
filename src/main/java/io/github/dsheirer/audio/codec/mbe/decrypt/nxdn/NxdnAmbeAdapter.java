/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.audio.codec.mbe.decrypt.nxdn;

import io.github.dsheirer.audio.codec.mbe.decrypt.AmbeProtectedVoiceAdapter;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptionException;

/**
 * Converts between the transmitted NXDN 72-bit EHR frame and its 49 protected AMBE+2 voice bits.
 */
public class NxdnAmbeAdapter
{
    private static final AmbeProtectedVoiceAdapter.Interleave INTERLEAVE =
        new AmbeProtectedVoiceAdapter.Interleave("NXDN AMBE privacy",
            new int[] {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 64, 68,
                1, 5, 9, 13, 17, 21},
            new int[] {25, 29, 33, 37, 41, 45, 49, 53, 57, 61, 65, 69, 2, 6, 10, 14, 18, 22,
                26, 30, 34, 38, 42},
            new int[] {46, 50, 54, 58, 62, 66, 70, 3, 7, 11, 15},
            new int[] {19, 23, 27, 31, 35, 39, 43, 47, 51, 55, 59, 63, 67, 71});

    private NxdnAmbeAdapter()
    {
    }

    public static boolean[] decode(byte[] frame) throws VoiceFrameDecryptionException
    {
        return AmbeProtectedVoiceAdapter.decode9ToVoice49(frame, INTERLEAVE);
    }

    public static byte[] encode(boolean[] voice) throws VoiceFrameDecryptionException
    {
        return AmbeProtectedVoiceAdapter.encodeVoice49To9(voice, INTERLEAVE);
    }
}
