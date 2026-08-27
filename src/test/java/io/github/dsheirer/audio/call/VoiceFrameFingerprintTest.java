/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class VoiceFrameFingerprintTest
{
    @Test
    void isStableSensitiveAndReservesZeroForUnavailableFrames()
    {
        byte[] frame = {0x01, 0x23, (byte)0xFE, 0x45};

        assertEquals(VoiceFrameFingerprint.compute(frame), VoiceFrameFingerprint.compute(frame.clone()));
        assertNotEquals(VoiceFrameFingerprint.compute(frame),
            VoiceFrameFingerprint.compute(new byte[]{0x01, 0x23, (byte)0xFF, 0x45}));
        assertEquals(0, VoiceFrameFingerprint.compute(null));
        assertEquals(0, VoiceFrameFingerprint.compute(new byte[0]));
        assertNotEquals(0, VoiceFrameFingerprint.compute(frame));
    }
}
