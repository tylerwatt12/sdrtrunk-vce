/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Adds one sparse source Working-ID evidence index without changing retained events. */
final class Format32To33DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-32-to-33"; }
    @Override public String description() { return "Speed up P25 radio alias lookups"; }
    @Override public int sourceVersion() { return 32; }
    @Override public int targetVersion() { return 33; }

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
        ReceiverActivitySchema.createSourceWorkingEvidenceIndex(connection);
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 32)
        {
            throw new SQLException("Expected format 32");
        }
    }

    private static List<DatabaseMigrationEffect> effects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "source Working-ID evidence lookup index", 1,
            "Add one sparse covering index while preserving every retained activity event, identity " +
                "relationship, administrator setting, credential, and personal preference unchanged"));
    }
}
