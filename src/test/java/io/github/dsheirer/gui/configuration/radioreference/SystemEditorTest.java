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

package io.github.dsheirer.gui.configuration.radioreference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.rrapi.type.System;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemEditorTest
{
    @Test
    void sortsSystemsByNewestRadioReferenceUpdateFirst()
    {
        System oldest = system(1, "Old System", "2026-01-01T12:00:00Z");
        System newest = system(2, "New System", "2026-07-31T12:00:00Z");
        System middle = system(3, "Middle System", "2026-04-01T12:00:00Z");

        assertEquals(List.of(newest, middle, oldest),
            SystemEditor.sortedSystems(List.of(oldest, newest, middle)));
    }

    @Test
    void sortsEqualAndMissingDatesDeterministically()
    {
        System zulu = system(1, "Zulu", "2026-07-31T12:00:00Z");
        System alpha = system(2, "alpha", "2026-07-31T12:00:00Z");
        System unknown = system(3, "Unknown Date", null);

        assertEquals(List.of(alpha, zulu, unknown),
            SystemEditor.sortedSystems(List.of(unknown, zulu, alpha)));
    }

    private static System system(int id, String name, String lastUpdated)
    {
        System system = new System();
        system.setSystemId(id);
        system.setName(name);
        system.setLastUpdated(lastUpdated != null ? Date.from(Instant.parse(lastUpdated)) : null);
        return system;
    }
}
