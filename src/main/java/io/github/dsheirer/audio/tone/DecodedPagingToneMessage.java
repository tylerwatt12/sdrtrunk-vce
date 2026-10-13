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
package io.github.dsheirer.audio.tone;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.tone.DetectedPagingToneIdentifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.Objects;

/** Completed-call audio inference with the original physical call and carrier/sample-clock provenance. */
public record DecodedPagingToneMessage(AudioCallId callId, CallLegId callLegId, CallLegSource callLegSource,
                                       long callStartTimestamp, long firstVoiceFrameTimestamp,
                                       long completedTimestamp, DetectedPagingToneIdentifier toneIdentifier)
    implements IMessage
{
    public DecodedPagingToneMessage
    {
        Objects.requireNonNull(callId);
        Objects.requireNonNull(callLegId);
        Objects.requireNonNull(callLegSource);
        Objects.requireNonNull(toneIdentifier);
    }

    @Override
    public long getTimestamp()
    {
        //Voice-frame timestamps anchor the end of each native 20 ms block, not the first PCM sample.
        return Math.max(0L, firstVoiceFrameTimestamp - 20L) +
            toneIdentifier.getValue().getFirst().offsetMilliseconds();
    }

    @Override
    public boolean isValid() { return true; }

    @Override
    public Protocol getProtocol() { return Protocol.APCO25; }

    @Override
    public int getTimeslot() { return callId.timeslot(); }

    @Override
    public List<Identifier> getIdentifiers() { return List.of(toneIdentifier); }

    @Override
    public String toString()
    {
        return "P25 Phase 1 sustained tones detected from decoded audio: " + toneIdentifier;
    }
}
