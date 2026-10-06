/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Replaces one reproducible index without changing retained rows, keys, or the number of indexes. */
final class Format41To42DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-41-to-42"; }
    @Override public String description() { return "Keep radio identity cleanup responsive"; }
    @Override public int sourceVersion() { return 41; }
    @Override public int targetVersion() { return 42; }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects() { return effects(); }

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
        ReceiverActivitySchema.rebuildTargetIdentityForeignKeyIndex(connection);
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 41)
        {
            throw new SQLException("Expected format 41");
        }
    }

    private static List<DatabaseMigrationEffect> effects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "target identity lookup index", 1,
            "Rebuild one existing index to cover target identity foreign-key checks, preserving every retained row, " +
                "time/ID order, allocator, relationship, administrator setting, credential, and personal preference"));
    }
}
