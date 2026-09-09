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

import io.github.dsheirer.module.ModuleEventBusMessage;

/** Publishes the exact control-channel processing and tuning generation that can verify call placement. */
public record SiteEvidenceProcessingIncarnationEvent(long processingIncarnation, long tuningGeneration)
    implements ModuleEventBusMessage
{
    public SiteEvidenceProcessingIncarnationEvent
    {
        if(processingIncarnation <= 0L)
        {
            throw new IllegalArgumentException("Site-evidence processing incarnation must be positive");
        }

        if(tuningGeneration <= 0L)
        {
            throw new IllegalArgumentException("Site-evidence tuning generation must be positive");
        }
    }
}
