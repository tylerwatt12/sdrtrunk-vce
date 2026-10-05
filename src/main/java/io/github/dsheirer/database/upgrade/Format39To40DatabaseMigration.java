/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Adds two reproducible positive local-address indexes without rewriting retained rows. */
final class Format39To40DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-39-to-40"; }
    @Override public String description() { return "Speed up retained identity name search"; }
    @Override public int sourceVersion() { return 39; }
    @Override public int targetVersion() { return 40; }

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
        ReceiverActivitySchema.createIdentityLocalAddressEvidenceIndexes(connection);
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 39)
        {
            throw new SQLException("Expected format 39");
        }
    }

    private static List<DatabaseMigrationEffect> effects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "positive source and target local-address lookup indexes", 2,
            "Add two sparse covering indexes while preserving every retained row, allocator, " +
                "identity relationship, administrator setting, credential, and personal preference unchanged"));
    }
}
