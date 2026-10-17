/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.record.managed;

import io.github.dsheirer.audio.call.SourceCallReference;
import java.util.LinkedHashSet;
import java.util.List;

/** Bounded canonical source references shared by the catalog writer and its exact lookup APIs. */
final class ManagedRecordingSourceReferences
{
    private ManagedRecordingSourceReferences() {}

    static List<String> copy(List<String> references, int maximum)
    {
        if(references == null || references.isEmpty()) return List.of();
        if(references.size() > maximum) throw new IllegalArgumentException("Too many source call references");
        var unique = new LinkedHashSet<String>();
        for(String reference: references)
        {
            if(reference == null || SourceCallReference.canonical(reference) == null)
                throw new IllegalArgumentException("Source call reference must be a canonical UUID");
            unique.add(reference);
        }
        return List.copyOf(unique);
    }
}
