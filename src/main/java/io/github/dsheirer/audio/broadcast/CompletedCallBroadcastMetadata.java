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
package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallLegSummary;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import java.util.List;

/**
 * Immutable completed-call facts retained beside an encoded streaming file.
 *
 * <p>The PCM buffers are deliberately excluded. This lets a broadcaster durably project call and reception facts
 * after encoding without retaining a second copy of the call audio in memory.</p>
 */
public record CompletedCallBroadcastMetadata(AudioCallSnapshot snapshot, List<CallLegSummary> callLegSummaries)
{
    public static final CompletedCallBroadcastMetadata EMPTY = new CompletedCallBroadcastMetadata(null, List.of());

    public CompletedCallBroadcastMetadata
    {
        callLegSummaries = callLegSummaries != null ? List.copyOf(callLegSummaries) : List.of();
    }

    public static CompletedCallBroadcastMetadata from(CompletedAudioCall call)
    {
        return call != null ? new CompletedCallBroadcastMetadata(call.snapshot(), call.callLegSummaries()) : EMPTY;
    }

    public boolean isAvailable()
    {
        return snapshot != null;
    }
}
