/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Adds two reproducible lookup indexes without rewriting retained rows or existing indexes. */
final class Format37To38DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-37-to-38"; }
    @Override public String description() { return "Speed up retained statistics filters"; }
    @Override public int sourceVersion() { return 37; }
    @Override public int targetVersion() { return 38; }

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
        ReceiverActivitySchema.createRemainingQueryLookupIndexes(connection);
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
            "retained statistics lookup indexes", 2,
            "Add two covering lookup indexes while preserving every existing schema definition, retained row, " +
                "allocator, identity relationship, administrator setting, credential, and personal preference unchanged"));
    }
}
