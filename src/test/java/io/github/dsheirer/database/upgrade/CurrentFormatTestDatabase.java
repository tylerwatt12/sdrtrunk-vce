/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;

/** Populated current fixture selected by the catalog, leaving every historical factory frozen. */
public final class CurrentFormatTestDatabase
{
    private CurrentFormatTestDatabase() {}

    public static String lastMigrationStepId()
    {
        return DatabaseMigrationChain.steps().getLast().id();
    }

    public static Path create(Path database) throws Exception
    {
        String factory = Path.of(DatabaseFormatCatalog.current().fixtureResource()).getFileName().toString()
            .replace(".java", "");
        return (Path)Class.forName(CurrentFormatTestDatabase.class.getPackageName() + "." + factory)
            .getMethod("create", Path.class).invoke(null, database);
    }
}
