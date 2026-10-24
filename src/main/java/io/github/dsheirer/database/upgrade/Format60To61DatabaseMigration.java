/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Adds independently stored account tour history without guessing browser-local dismissals. */
final class Format60To61DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-60-to-61"; }
    @Override public String description() { return "Remember tours for each web account"; }
    @Override public int sourceVersion() { return 60; }
    @Override public int targetVersion() { return 61; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "accounts and receiver data", 0,
            "Preserve every account, credential, preference, setting, retained row, relationship and allocator"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "account tour history", 1,
                "Add one empty account-owned table; browser-local dismissals are imported by their signed-in browser"));
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }
    @Override public void migrateSource(Connection connection) throws SQLException
    {
        SdrTrunkDatabaseSchema.createWebTourDismissalTable(connection);
    }
}
