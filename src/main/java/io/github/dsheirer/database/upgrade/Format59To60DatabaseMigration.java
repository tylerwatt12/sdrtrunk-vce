/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Shares message definitions and retains exact prospective control operations without guessing old subtypes. */
final class Format59To60DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-59-to-60"; }
    @Override public String description() { return "Share Activity message names and meanings"; }
    @Override public int sourceVersion() { return 59; }
    @Override public int targetVersion() { return 60; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "configuration and retained Activity", 0,
            "Preserve every row, ID, relationship, setting, counter and allocator; leave historical command subtypes unknown"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "shared message definitions", 1,
                "Create one bounded message catalogue and admit prospective control categories with zero-initialized summary counters"));
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }
    @Override public void migrateSource(Connection connection) throws SQLException
    {
        ReceiverActivitySchema.upgradeFrom59(connection);
    }
}
