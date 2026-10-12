/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.diagnostic;

import java.util.List;

/** Receiver-published trusted frequencies; this list is never a claim that a trunked site's frequencies are complete. */
public record TunerAllocationKnownSite(TunerAllocationContext context, List<Frequency> frequencies)
{
    public TunerAllocationKnownSite { frequencies = List.copyOf(frequencies); }
    public record Frequency(long frequencyHz, long bandwidthHz, TunerAllocationContext.Role role) { }
}
