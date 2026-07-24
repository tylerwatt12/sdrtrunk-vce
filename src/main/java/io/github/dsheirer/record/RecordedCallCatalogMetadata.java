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
package io.github.dsheirer.record;

import java.util.Objects;

/**
 * Public metadata for one retained recorded call.
 *
 * <p>This projection deliberately omits the managed filesystem path. Public callers use the opaque call ID with the
 * separately gated media resolver instead of learning the receiver's recording layout.</p>
 */
public record RecordedCallCatalogMetadata(String id, long completedAtMs, long startAtMs, long durationMs,
                                          long byteSize, RecordFormat format, boolean encrypted,
                                          RecordedCallIdentity system, RecordedCallIdentity site,
                                          RecordedCallIdentity channel, RecordedCallIdentity talkgroup,
                                          RecordedCallIdentity sourceRadio)
{
    public RecordedCallCatalogMetadata
    {
        RecordedCallCatalogTokens.CursorValues values = RecordedCallCatalogTokens.parseCallId(id);

        if(values.completedAtMs() != completedAtMs)
        {
            throw new IllegalArgumentException("Recorded-call public ID does not match its completion time");
        }

        Objects.requireNonNull(format, "Recorded-call format cannot be null");
    }
}
