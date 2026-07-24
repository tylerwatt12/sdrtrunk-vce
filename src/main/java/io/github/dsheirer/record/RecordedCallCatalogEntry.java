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

import java.nio.file.Path;
import java.util.Objects;

/**
 * One retained recorded call returned to a bounded web query.
 */
public record RecordedCallCatalogEntry(String id, long completedAtMs, long startAtMs, long durationMs,
                                       long byteSize, RecordFormat format, boolean encrypted, Path relativePath,
                                       RecordedCallIdentity system, RecordedCallIdentity site,
                                       RecordedCallIdentity channel, RecordedCallIdentity talkgroup,
                                       RecordedCallIdentity sourceRadio)
{
    public RecordedCallCatalogEntry
    {
        RecordedCallCatalogTokens.CursorValues values = RecordedCallCatalogTokens.parseCallId(id);

        if(values.completedAtMs() != completedAtMs)
        {
            throw new IllegalArgumentException("Recorded-call public ID does not match its completion time");
        }
        Objects.requireNonNull(format, "Recorded-call format cannot be null");
        Objects.requireNonNull(relativePath, "Recorded-call relative path cannot be null");
    }

    public RecordedCallCatalogSearch.Cursor cursor()
    {
        return RecordedCallCatalogSearch.Cursor.create(completedAtMs,
            RecordedCallCatalogTokens.parseCallId(id).callId());
    }
}
