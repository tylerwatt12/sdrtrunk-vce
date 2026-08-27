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
package io.github.dsheirer.audio.call;

/**
 * Fixed-cost fingerprint for one received or successfully decrypted vocoder frame.
 *
 * <p>This is evidence for matching receiver legs, not a cryptographic digest.  Zero is reserved to mean that no
 * fingerprint was available.</p>
 */
public final class VoiceFrameFingerprint
{
    private static final long FNV_OFFSET_BASIS = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    private VoiceFrameFingerprint()
    {
    }

    public static long compute(byte[] frame)
    {
        if(frame == null || frame.length == 0)
        {
            return 0;
        }

        long fingerprint = FNV_OFFSET_BASIS;

        for(byte value: frame)
        {
            fingerprint ^= value & 0xFFL;
            fingerprint *= FNV_PRIME;
        }

        return fingerprint != 0 ? fingerprint : 1;
    }
}
