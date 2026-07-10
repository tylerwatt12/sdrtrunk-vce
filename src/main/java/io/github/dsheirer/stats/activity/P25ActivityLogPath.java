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

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.preference.UserPreferences;
import java.nio.file.Path;

/**
 * Path helper for P25 activity tables in the global SQLite database.
 */
public final class P25ActivityLogPath
{
    private P25ActivityLogPath()
    {
    }

    /**
     * Global SDRTrunk database path under the application root.
     */
    public static Path getDatabasePath(UserPreferences userPreferences)
    {
        return SdrTrunkDatabasePath.getDatabasePath(userPreferences);
    }
}
