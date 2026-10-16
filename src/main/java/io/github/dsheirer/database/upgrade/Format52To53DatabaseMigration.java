/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Establishes explicit SQLite ownership for receiver connection and audio settings. */
final class Format52To53DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-52-to-53"; }
    @Override public String description() { return "Keep Remote Links and audio settings in the application database"; }
    @Override public int sourceVersion() { return 52; }
    @Override public int targetVersion() { return 53; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT);
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        return effects(Format53ReceiverSettingsSchema.countMissing(connection));
    }
    @Override public void migrateSource(Connection connection) throws SQLException
    {
        Format53ReceiverSettingsSchema.initializeMissing(connection);
    }
    private static List<DatabaseMigrationEffect> effects(long defaults)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "saved configuration, credentials and receiver history", 0,
            "Preserve every valid existing database row, including any saved Remote Links and audio choices"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "database-owned Remote Links and audio settings", defaults,
                "Create absent settings with Remote Links disabled, paging tones enabled and voice enhancement disabled; " +
                    "refuse malformed existing database values"));
    }
}
