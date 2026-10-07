/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Adds one identity-first retention lookup index without changing retained activity. */
final class Format36To37DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-36-to-37"; }
    @Override public String description() { return "Speed up receiver identity cleanup"; }
    @Override public int sourceVersion() { return 36; }
    @Override public int targetVersion() { return 37; }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects();
    }

    @Override
    public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        return effects();
    }

    @Override
    public void migrateSource(Connection connection) throws SQLException
    {
        ReceiverActivitySchema.createIdentityRetentionIndexes(connection);
    }


    private static List<DatabaseMigrationEffect> effects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "receiver history cleanup", 1,
            "Speed up radio identity cleanup while preserving all saved history, counters, receiver settings, " +
                "accounts, and personal preferences"));
    }
}
