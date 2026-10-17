/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.call;

import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;

/** Exact producer binding on one processing-chain-local bus; never a global receiver observation. */
public record AudioActivityCallEvent(String token, int timeslot, CallAttribution attribution,
                                     long physicalBoundaryTimestamp)
{
    public AudioActivityCallEvent
    {
        if(attribution != null && !java.util.Objects.equals(token, attribution.token()))
            throw new IllegalArgumentException("Attribution must belong to the exact call token");
    }

    public AudioActivityCallEvent(String token, int timeslot, CallAttribution attribution)
    {
        this(token, timeslot, attribution, 0L);
    }

    public AudioActivityCallEvent(String token, int timeslot)
    {
        this(token, timeslot, null);
    }

    /** A physical start can precede delivery of the same header to the audio producer. */
    public AudioActivityCallEvent withPhysicalBoundary(long timestamp)
    {
        return new AudioActivityCallEvent(token, timeslot, attribution, timestamp);
    }

    /** Projects ancillary RF facts using the source accepted for that exact selected call. */
    public static MutableIdentifierCollection projectAcceptedSource(AudioActivityCallEvent binding,
                                                                    IdentifierCollection context)
    {
        MutableIdentifierCollection projected = new MutableIdentifierCollection(context.getIdentifiers(),
            context.getTimeslot());
        for(var identifier: context.getIdentifiers())
            if(IdentifierCollection.isSourceIdentifier(identifier)) projected.remove(identifier);
        if(binding != null && binding.attribution() != null) projected.update(binding.attribution().source());
        return projected;
    }
}
