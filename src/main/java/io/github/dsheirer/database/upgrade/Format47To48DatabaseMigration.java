/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Removes retired personal suppression without changing notification ownership or rule schedules. */
final class Format47To48DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-47-to-48"; }
    @Override public String description() { return "Remove personal notification pause and quiet hours"; }
    @Override public int sourceVersion() { return 47; }
    @Override public int targetVersion() { return 48; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "notification ownership, topics, rules, history and delivery", 0,
            "Preserve guest tokens, topic choices, subscription expiry, pairing, retained alerts and rule schedules including their time zones"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "retired personal notification preferences", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Remove personal pause, quiet-hour and time-zone settings from devices and enrollment sessions; existing subscriptions receive normally"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "protocol family rule scope", 0,
                "Keep existing rule scope unchanged; an absent protocol family means all source decoder families"));
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        long count;
        try(Statement sql = connection.createStatement(); var row = sql.executeQuery("SELECT (SELECT count(*) FROM topic_alert_device WHERE paused<>0 OR quiet_start<>'' OR quiet_end<>'' OR time_zone<>'UTC')+(SELECT count(*) FROM topic_alert_enrollment WHERE paused<>0 OR quiet_start<>'' OR quiet_end<>'' OR time_zone<>'UTC')"))
        { row.next(); count = row.getLong(1); }
        var effects = declaredEffects();
        return List.of(effects.get(0), new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
            effects.get(1).subject(), count, effects.get(1).detail()), effects.get(2));
    }
    @Override public void migrateSource(Connection connection) throws SQLException
    {
        try(Statement sql = connection.createStatement())
        {
            for(String table: List.of("topic_alert_device", "topic_alert_enrollment"))
                for(String column: List.of("paused", "quiet_start", "quiet_end", "time_zone"))
                    sql.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
        }
    }
}
