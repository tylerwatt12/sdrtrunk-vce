/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.identifier.IdentifierCollection;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AudioCallEventTest
{
    @Test
    void audioFrameCarriesExplicitCarrierTimestamp()
    {
        float[] audio = new float[]{0.25f, -0.5f};
        AudioCallEvent event = new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, snapshot(), audio,
            false, 0x1234L, 1_020L);

        audio[0] = 1.0f;
        assertEquals(1_020L, event.voiceFrameTimestamp());
        assertEquals(0.25f, event.audioFrame()[0]);
        assertNotSame(event.audioFrame(), event.audioFrame());
    }

    @Test
    void audioFrameRejectsMissingCarrierTimestamp()
    {
        assertThrows(IllegalArgumentException.class, () ->
            new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, snapshot(), new float[160], false, 0L, 0L));
    }

    @Test
    void lifecycleEventRejectsVoiceFrameEvidence()
    {
        assertThrows(IllegalArgumentException.class, () ->
            new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, snapshot(), null, false, 1L, 1_020L));
    }

    private static AudioCallSnapshot snapshot()
    {
        AudioCallId callId = new AudioCallId(1L, 1L, 0);
        return new AudioCallSnapshot(callId, null, null, new IdentifierCollection(),
            Set.of(), 1_000L, 1_020L, 1, 1L, 1_000L, 1_020L, true, false,
            CallEncryptionState.CLEAR, false, null, VoiceCallQuality.EMPTY, CallLegId.from(callId), null, null);
    }
}
