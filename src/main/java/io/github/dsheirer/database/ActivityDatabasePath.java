/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database;

import java.nio.file.Path;

/** The receiver-owned activity companion always lives beside its configuration database. */
public final class ActivityDatabasePath
{
    public static final String DATABASE_FILENAME = "activity.sqlite";

    private ActivityDatabasePath()
    {
    }

    public static Path forConfiguration(Path configuration)
    {
        String name = configuration.getFileName().toString();
        return configuration.resolveSibling(name.equals(SdrTrunkDatabasePath.DATABASE_FILENAME) ?
            DATABASE_FILENAME : name + ".activity.sqlite");
    }

    /** Private config extraction owned by the format-62-to-63 Application Migrator transition. */
    public static Path configurationStage(Path configuration)
    {
        return configuration.resolveSibling("." + configuration.getFileName() + ".configuration-format63");
    }

    public static Path splitReceipt(Path configuration)
    {
        return configuration.resolveSibling("." + configuration.getFileName() + ".split-format63");
    }
}
