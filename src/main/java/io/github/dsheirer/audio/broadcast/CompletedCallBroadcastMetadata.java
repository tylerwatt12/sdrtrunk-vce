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
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import java.util.List;

/**
 * Immutable completed-call facts retained beside an encoded streaming file.
 *
 * <p>The PCM buffers are deliberately excluded. This lets a broadcaster durably project call and reception facts
 * after encoding without retaining a second copy of the call audio in memory.</p>
 */
public record CompletedCallBroadcastMetadata(AudioCallSnapshot snapshot, List<CallLegSummary> callLegSummaries,
                                             String servingSystemKey, List<Identifier> recordingIdentifiers)
{
    public static final CompletedCallBroadcastMetadata EMPTY = new CompletedCallBroadcastMetadata(null, List.of());

    public CompletedCallBroadcastMetadata(AudioCallSnapshot snapshot, List<CallLegSummary> callLegSummaries)
    {
        this(snapshot, callLegSummaries, snapshot != null ?
            BroadcastIdentifierFormatter.servingSystemKey(snapshot.callLegSource(), null) : null, null);
    }

    /** Compatibility constructor for metadata that did not retain the winner's recording observations. */
    public CompletedCallBroadcastMetadata(AudioCallSnapshot snapshot, List<CallLegSummary> callLegSummaries,
                                          String servingSystemKey)
    {
        this(snapshot, callLegSummaries, servingSystemKey, null);
    }

    public CompletedCallBroadcastMetadata
    {
        callLegSummaries = callLegSummaries != null ? List.copyOf(callLegSummaries) : List.of();
        //Null distinguishes older metadata from a captured winner that had no observed identifiers.
        recordingIdentifiers = recordingIdentifiers != null ? List.copyOf(recordingIdentifiers) : null;
    }

    public static CompletedCallBroadcastMetadata from(CompletedAudioCall call)
    {
        if(call == null)
        {
            return EMPTY;
        }
        IdentifierCollection observed = call.recordingIdentifierCollection();
        return new CompletedCallBroadcastMetadata(call.snapshot(), call.callLegSummaries(),
            BroadcastIdentifierFormatter.servingSystemKey(call.snapshot().callLegSource(), observed),
            observed != null ? observed.getIdentifiers() : List.of());
    }

    public boolean isAvailable()
    {
        return snapshot != null;
    }
}
