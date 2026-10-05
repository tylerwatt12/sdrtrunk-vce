/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Rebuilds four activity lookup indexes and adds a narrow event projection without changing retained rows. */
final class Format37To38DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-37-to-38"; }
    @Override public String description() { return "Speed up activity rankings and identity name lookups"; }
    @Override public int sourceVersion() { return 37; }
    @Override public int targetVersion() { return 38; }

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
        ReceiverActivitySchema.rebuildCoveringActivityIndexes(connection);
        ReceiverActivitySchema.rebuildHistoricalMemberEvidenceIndexes(connection);
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 37)
        {
            throw new SQLException("Expected format 37");
        }
    }

    private static List<DatabaseMigrationEffect> effects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "covering activity lookup indexes", 4,
            "Rebuild four indexes while preserving every retained row, event ID, identity relationship, " +
                "administrator setting, credential, and personal preference unchanged"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "activity event/channel lookup index", 1,
                "Add one narrow covering event/channel index for historical member evidence without adding " +
                    "events or replacing the unique parent key used by identity-member foreign keys"));
    }
}
