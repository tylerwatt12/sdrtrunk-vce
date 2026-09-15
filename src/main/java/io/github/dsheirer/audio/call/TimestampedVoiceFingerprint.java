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
 * Immutable, bounded duplicate-detection evidence for one received or successfully decrypted vocoder frame.
 *
 * <p>The fingerprint is not audio and cannot be decoded back into voice. The carrier timestamp preserves the frame's
 * relative position so matching fingerprints from separate receivers must also have one consistent time offset.</p>
 */
public record TimestampedVoiceFingerprint(long fingerprint, long carrierTimestamp)
{
    public TimestampedVoiceFingerprint
    {
        if(fingerprint == 0L)
        {
            throw new IllegalArgumentException("Voice-frame fingerprint cannot be zero");
        }

        if(carrierTimestamp <= 0L)
        {
            throw new IllegalArgumentException("Voice-frame carrier timestamp must be positive");
        }
    }
}
