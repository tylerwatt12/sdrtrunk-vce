/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Retains only prospective accepted-call timing; historical durations remain unknown. */
final class Format58To59DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-58-to-59"; }
    @Override public String description() { return "Store accepted call length in Activity"; }
    @Override public int sourceVersion() { return 58; }
    @Override public int targetVersion() { return 59; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "configuration, Activity and exact call references", 0,
            "Preserve every row, ID, relationship, setting, counter and allocator; historical call lengths remain unknown"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "optional Activity call length", 1,
                "Add nullable duration milliseconds to existing call rows without audio or history backfill"));
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }
    @Override public void migrateSource(Connection connection) throws SQLException
    {
        ReceiverActivitySchema.addCallDuration(connection);
    }
}
