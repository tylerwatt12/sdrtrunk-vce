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
 * Immutable provenance and quality summary for one physical receiver leg in a resolved logical call.  Audio is held
 * only by the elected winner; this compact summary lets statistics count learned-site observations independently
 * from the single logical call without retaining losing audio.
 */
public record CallLegSummary(CallLegId callLegId, CallLegSource source, long startTimestamp, long endTimestamp,
                             VoiceCallQuality voiceCallQuality, long retainedAudioSampleCount,
                             boolean ingressLoss, boolean audioTruncated, boolean winner,
                             CallEncryptionEvidence callEncryptionEvidence)
{
    public CallLegSummary
    {
        if(callLegId == null)
        {
            throw new IllegalArgumentException("Call leg id is required");
        }

        source = source != null ? source : CallLegSource.UNKNOWN;
        startTimestamp = Math.max(0L, startTimestamp);
        endTimestamp = Math.max(startTimestamp, endTimestamp);
        voiceCallQuality = voiceCallQuality != null ? voiceCallQuality : VoiceCallQuality.EMPTY;
        retainedAudioSampleCount = Math.max(0L, retainedAudioSampleCount);
    }
}
