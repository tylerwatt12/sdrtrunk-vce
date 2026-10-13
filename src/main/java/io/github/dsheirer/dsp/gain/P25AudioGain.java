/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.dsp.gain;

import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.module.decode.DecoderType;

/** Presentation gain for native P25 IMBE encoding. Call only on a completed recording/stream worker. */
public final class P25AudioGain
{
    private static final PeakAudioGain IMBE_GAIN = new PeakAudioGain(5.0f, 0.95f);

    private P25AudioGain() {}

    /**
     * Creates an encoding view without modifying the raw call, its captured policy, metadata or sample clock.
     * Only the elected Phase 1/conventional decoder owns IMBE gain; Phase 2 and unknown sources remain unchanged.
     */
    public static CompletedAudioCall forEncoding(CompletedAudioCall rawCall)
    {
        DecoderType decoderType = rawCall.snapshot().callLegSource().decoderType();

        if(decoderType != DecoderType.P25_PHASE1 && decoderType != DecoderType.P25_CONVENTIONAL)
        {
            return rawCall;
        }

        return new CompletedAudioCall(rawCall.logicalCallId(), rawCall.snapshot(),
            IMBE_GAIN.process(rawCall.audioBuffers()), rawCall.resolvedPolicy(), rawCall.callLegSummaries(),
            rawCall.recordingIdentifierCollection(), rawCall.nativeAudioSnapshot(), rawCall.completedToneSequence());
    }
}
