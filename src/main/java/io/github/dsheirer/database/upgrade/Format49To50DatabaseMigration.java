/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ToneActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Adds prospective structured tone history while preserving every existing observation and setting. */
final class Format49To50DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-49-to-50"; }
    @Override public String description() { return "Retain ordered tone sequences in Activity"; }
    @Override public int sourceVersion() { return 49; }
    @Override public int targetVersion() { return 50; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "configuration and retained Activity", 0,
            "Preserve every setting, account, credential, observation, identity relationship, counter and allocator; do not infer historical tones"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "prospective tone Activity", 2,
                "Add two empty bounded tone-fact tables and exact TONE categories; retain existing Activity indexes and browse ordering"));
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    { return declaredEffects(); }
    @Override public void migrateSource(Connection connection) throws SQLException { ToneActivitySchema.upgradeFrom49(connection); }
}
