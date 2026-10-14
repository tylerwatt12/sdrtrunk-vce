/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Extends prospective Alias accounting without guessing attribution for old analog history. */
final class Format50To51DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-50-to-51"; }
    @Override public String description() { return "Count analog Alias calls and observed assigned numbers"; }
    @Override public int sourceVersion() { return 50; }
    @Override public int targetVersion() { return 51; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "configuration, digital Alias counters and retained Activity", 0,
            "Preserve every configured entity, digital counter, observation, relationship and allocator"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "prospective analog Alias statistics", 1,
                "Enable AM/NBFM exact and range Alias counters and empty list/channel/assigned-number summaries; infer no old matches"));
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }
    @Override public void migrateSource(Connection connection) throws SQLException
    {
        SdrTrunkDatabaseSchema.upgradeAliasActivityFrom50(connection);
    }
}
