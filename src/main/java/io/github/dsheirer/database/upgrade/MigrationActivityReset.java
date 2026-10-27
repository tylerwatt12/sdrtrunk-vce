/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.AliasActivitySummaryMaintenance;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Explicit user-requested receiver-history discard; configuration and recording artifacts are outside its scope. */
final class MigrationActivityReset
{
    static final String STEP_ID = "discard-receiver-history-by-choice";
    private static final String DESCRIPTION = "Keep configuration and start receiver Activity and statistics fresh";
    private static final String SUBJECT = "receiver Activity and statistics";

    private MigrationActivityReset() { }

    static DatabaseMigrationChain.StepPreflight preflight(int sourceVersion)
    {
        return new DatabaseMigrationChain.StepPreflight(STEP_ID, DESCRIPTION, sourceVersion, sourceVersion,
            effects(DatabaseMigrationEffect.UNKNOWN_COUNT, DatabaseMigrationEffect.UNKNOWN_COUNT,
                DatabaseMigrationEffect.UNKNOWN_COUNT));
    }

    static long discard(Connection connection) throws SQLException
    {
        return discard(connection, MigrationTimings.NONE);
    }

    static long discard(Connection connection, MigrationTimings timings) throws SQLException
    {
        //Use only known historical/current receiver-owned names. Never classify by a broad table-name prefix:
        //canonical P25 subscriber tuples also support configured Aliases and must survive.
        Set<String> present = new LinkedHashSet<>();
        try(var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT name FROM sqlite_schema WHERE type='table'"))
        {
            while(rows.next()) present.add(rows.getString(1));
        }
        Set<String> classified = new LinkedHashSet<>(LegacyActivityReset.PRE_LOGICAL_CALL_TABLES);
        classified.addAll(LegacyActivityReset.LOGICAL_CALL_TABLES);
        classified.addAll(CurrentDatabaseDerivedStateRepair.REPRODUCIBLE_TABLES);
        classified.add("alias_list_analog_activity_summary");
        classified.add("topic_alert_delivery");
        classified.add("topic_alert_message");
        classified.retainAll(present);
        List<String> tables = List.copyOf(classified);
        long rows;
        try(var phase = timings.phase("Count discarded receiver history rows"))
        {
            rows = LegacyActivityReset.count(connection, tables);
            phase.complete();
        }
        try(var phase = timings.phase("Clear discarded receiver history tables"))
        {
            LegacyActivityReset.clear(connection, tables);
            phase.complete();
        }
        return rows;
    }

    static DatabaseMigrationChain.StepReport finish(Connection connection, int sourceVersion, long discardedRows)
        throws SQLException
    {
        //Every historical schema has reached current before these current-only counter columns are used.
        AliasActivitySummaryMaintenance.resetAll(connection);
        long aliasCounters;
        try(var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT count(*) FROM alias_activity_summary"))
        {
            aliasCounters = rows.next() ? rows.getLong(1) : 0;
        }
        long now = System.currentTimeMillis();
        boolean pairedConfiguration = "configuration".equals(io.github.dsheirer.database.ConfigurationHistorySchema.metadata(
            connection, io.github.dsheirer.database.ConfigurationHistorySchema.ROLE_KEY));
        String history = pairedConfiguration ? "activity." : "";
        try(var statement = connection.prepareStatement("""
            INSERT INTO %sdatabase_metadata(key,value,updated_at_ms) VALUES(?,?,?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at_ms=excluded.updated_at_ms
            """.formatted(history)))
        {
            for(String key: List.of(ReceiverActivitySchema.CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY,
                ReceiverActivitySchema.TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY,
                ReceiverActivitySchema.RADIO_SYSTEM_METRICS_STARTED_AT_KEY))
            {
                statement.setString(1, key); statement.setString(2, Long.toString(now));
                statement.setLong(3, now); statement.addBatch();
            }
            statement.executeBatch();
        }
        long analogWindows;
        boolean independentHistory = pairedConfiguration || "activity".equals(io.github.dsheirer.database.ConfigurationHistorySchema.metadata(
            connection,io.github.dsheirer.database.ConfigurationHistorySchema.ROLE_KEY));
        try(var statement = connection.prepareStatement(
            independentHistory ? "UPDATE " + history + "receiver_channel_statistics_reset SET reset_at_ms=?" :
                "UPDATE configuration_channel SET analog_activity_reset_ms=? WHERE analog_activity_reset_ms IS NOT NULL"))
        {
            statement.setLong(1, now); analogWindows = statement.executeUpdate();
        }
        return new DatabaseMigrationChain.StepReport(STEP_ID, DESCRIPTION, sourceVersion, sourceVersion,
            effects(discardedRows, aliasCounters, analogWindows));
    }

    private static List<DatabaseMigrationEffect> effects(long rows, long counters, long analogWindows)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET, SUBJECT, rows,
            "Discard receiver events, observed radios/systems/sites, signal-quality and receiver-health history, " +
            "activity counters/statistics and past alert messages/pending deliveries. Preserve configuration, " +
            "accounts, settings, subscriber Aliases, Topics/rules/devices/pairings and recording catalog/audio; " +
            "do not reconstruct discarded observations"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "empty Alias Activity counters", counters,
                "Seed empty current counters from saved Aliases without reconstructing old observations"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "receiver metric collection windows", 3,
                "Restart the three receiver metric collection windows"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "configured analog activity collection windows", analogWindows,
                "Restart existing configured-channel analog statistic boundaries"));
    }
}
