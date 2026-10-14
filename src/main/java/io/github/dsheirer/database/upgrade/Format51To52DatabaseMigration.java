/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ToneActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Versions prospective nominal PCM tone extraction without relabeling retained raw observations. */
final class Format51To52DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-51-to-52"; }
    @Override public String description() { return "Update paging-tone frequency reporting"; }
    @Override public int sourceVersion() { return 51; }
    @Override public int targetVersion() { return 52; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "configuration, Activity and tone evidence", 0,
            "Preserve every setting, account, credential, observation, counter, allocator and existing tone value"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "paging-tone frequency reporting", 1,
                "Use AMBE+2 frequencies for newly detected tones while preserving existing tone history"));
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }
    @Override public void migrateSource(Connection connection) throws SQLException { ToneActivitySchema.upgradeFrom51(connection); }
}
