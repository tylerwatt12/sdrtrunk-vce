/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Adds one identity-first retention lookup index without changing retained activity. */
final class Format35To36DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-35-to-36"; }
    @Override public String description() { return "Speed up receiver identity cleanup"; }
    @Override public int sourceVersion() { return 35; }
    @Override public int targetVersion() { return 36; }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects();
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSource(connection);
        return effects();
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        requireSource(connection);
        ReceiverActivitySchema.createIdentityRetentionIndexes(connection);
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 35)
        {
            throw new SQLException("Expected format 35");
        }
    }

    private static List<DatabaseMigrationEffect> effects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "receiver history cleanup", 1,
            "Speed up radio identity cleanup while preserving all saved history, counters, receiver settings, " +
                "accounts, and personal preferences"));
    }
}
