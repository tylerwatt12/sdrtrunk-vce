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

package io.github.dsheirer.database;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.portable.PortableApplicationPaths;
import java.nio.file.Path;

/**
 * Path helper for the global SDRTrunk SQLite database.
 */
public final class SdrTrunkDatabasePath
{
    public static final String DATABASE_DIRECTORY = "database";
    public static final String DATABASE_FILENAME = "sdrtrunk.sqlite";

    private SdrTrunkDatabasePath()
    {
    }

    public static Path getDatabasePath(UserPreferences userPreferences)
    {
        return userPreferences.getDirectoryPreference().getDirectoryApplicationRoot()
            .resolve(DATABASE_DIRECTORY)
            .resolve(DATABASE_FILENAME);
    }

    public static Path getDatabasePath()
    {
        return getDatabasePath(PortableApplicationPaths.getDataRoot());
    }

    public static Path getDatabasePath(Path applicationRoot)
    {
        return applicationRoot
            .resolve(DATABASE_DIRECTORY)
            .resolve(DATABASE_FILENAME);
    }
}
