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

import java.util.List;

/**
 * One immutable keyset-paginated catalog response.
 */
public record RecordedCallCatalogPage(List<RecordedCallCatalogEntry> calls,
                                      RecordedCallCatalogSearch.Cursor nextCursor)
{
    public RecordedCallCatalogPage
    {
        calls = calls != null ? List.copyOf(calls) : List.of();
    }
}
