/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.alerts.TopicAlertSchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Adds prospective exact call references without guessing any historical recording or alert relationship. */
final class Format57To58DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-57-to-58"; }
    @Override public String description() { return "Link new Activity and alerts to their exact source calls"; }
    @Override public int sourceVersion() { return 57; }
    @Override public int targetVersion() { return 58; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "configuration, Activity, alerts and delivery state", 0,
            "Preserve every stored row, ID, relationship, setting, counter and allocator; historical source call references remain unknown"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "optional source call reference fields", 2,
                "Add nullable source call UUIDs to Activity and alert messages without historical backfill"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "sparse source call lookup indexes", 2,
                "Index only known call references for exact local Activity, alert and recording lookups"));
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }
    @Override public void migrateSource(Connection connection) throws SQLException
    {
        ReceiverActivitySchema.addSourceCallReference(connection);
        TopicAlertSchema.addSourceCallReference(connection);
    }
}
