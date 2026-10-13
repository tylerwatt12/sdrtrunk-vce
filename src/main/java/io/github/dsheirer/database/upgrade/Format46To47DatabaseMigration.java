/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Adds explicit receiver-owned notification configuration, guest pairing, history, and bounded durable work. */
final class Format46To47DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-46-to-47"; }
    @Override public String description() { return "Add topic alerts with bounded notification history and delivery work"; }
    @Override public int sourceVersion() { return 46; }
    @Override public int targetVersion() { return 47; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "existing configuration and retained receiver history", 0,
            "Preserve every existing channel, Alias, credential, personal preference, and retained activity row"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "topic notification storage", 8,
                "Create eight explicit alert tables; start push delivery disabled and Administrator alerts hidden, with no inferred subscriptions or retained events"));
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) { return declaredEffects(); }
    @Override public void migrateSource(Connection connection) throws SQLException { Format47TopicAlertSchema.create(connection); }
}
