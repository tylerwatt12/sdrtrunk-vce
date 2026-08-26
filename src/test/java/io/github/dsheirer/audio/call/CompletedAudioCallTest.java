/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.audio.call;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.identifier.IdentifierCollection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompletedAudioCallTest
{
    @Test
    void oneLegSummaryUsesActualRetainedAudioSamples()
    {
        AudioCallId callId = new AudioCallId(1L, 1L, 0);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            new IdentifierCollection(), Set.of(), 1_000L, 2_000L, 1, 1L, 1_000L, 2_000L,
            false, true, CallEncryptionState.CLEAR, false, null, VoiceCallQuality.EMPTY,
            CallLegId.from(callId), null, null);

        CompletedAudioCall completed = new CompletedAudioCall(snapshot,
            List.of(new float[37], new float[123]));

        assertEquals(160L, completed.callLegSummaries().getFirst().retainedAudioSampleCount());
    }
}
