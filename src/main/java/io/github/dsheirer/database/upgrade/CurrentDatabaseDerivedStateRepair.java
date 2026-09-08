/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Same-format recovery for receiver-produced activity and statistics state.
 *
 * <p>Every table in this component is reproducible from future receiver observations.  If any one of its rows has
 * an invalid foreign key, violates the exact current table checks, or has unusable AUTOINCREMENT state, the safest
 * bounded repair is to clear the whole component, reset its derived identity high-water marks, and restart its three
 * collection boundaries. Administrator-owned channels, Aliases, providers, settings, and accounts are deliberately
 * outside this reset set.</p>
 */
final class CurrentDatabaseDerivedStateRepair
{
    static final String STEP_ID = "reset-damaged-current-derived-state";
    static final List<String> REPRODUCIBLE_TABLES = List.of(
        "radio_system",
        "radio_system_identity_summary",
        "trunked_radio_group_summary",
        "trunked_radio_affiliation",
        "trunked_radio_channel_presence",
        "trunked_radio_channel_presence_clear",
        "receiver_channel",
        "receiver_activity_event",
        "activity_event_identity_member",
        "p25_learned_site",
        "trunked_signaling_activity_bucket",
        "trunked_logical_call_bucket",
        "trunked_logical_call_identity_bucket",
        "p25_site_call_bucket",
        "p25_site_call_identity_bucket",
        "conventional_activity_summary",
        "conventional_activity_bucket",
        "conventional_call_identity_bucket",
        "p25_site_snapshot",
        "p25_site_channel",
        "p25_site_channel_summary",
        "p25_site_channel_tag",
        "p25_site_channel_tag_summary",
        "p25_site_frequency_band",
        "p25_site_frequency_band_summary",
        "p25_foreign_system_band",
        "p25_foreign_system_band_summary",
        "p25_site_neighbor",
        "p25_site_neighbor_summary",
        "p25_site_patch_group",
        "p25_site_patch_group_summary",
        "p25_site_patch_group_talkgroup",
        "p25_site_patch_group_talkgroup_summary",
        "p25_site_patch_group_radio",
        "p25_site_patch_group_radio_summary",
        "trunked_control_channel_quality",
        "dmr_conventional_talkgroup_summary",
        "dmr_conventional_radio_summary",
        "trunked_site_snapshot",
        "trunked_site_channel_summary",
        "trunked_site_neighbor_summary",
        "statistics_status");
    private static final Set<String> REPRODUCIBLE_TABLE_SET = Set.copyOf(REPRODUCIBLE_TABLES);
    private static final List<String> REPRODUCIBLE_AUTOINCREMENT_TABLES = List.of(
        "radio_system",
        "radio_system_identity_summary",
        "receiver_channel",
        "receiver_activity_event");
    private static final List<String> METRIC_BOUNDARY_KEYS = List.of(
        ReceiverActivitySchema.CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY,
        ReceiverActivitySchema.TRUNKED_LOGICAL_CALL_METRICS_STARTED_AT_KEY,
        ReceiverActivitySchema.RADIO_SYSTEM_METRICS_STARTED_AT_KEY);

    private CurrentDatabaseDerivedStateRepair()
    {
    }

    static boolean isReproducibleTable(String table)
    {
        return table != null && REPRODUCIBLE_TABLE_SET.contains(table);
    }

    static boolean isMetricBoundaryKey(String key)
    {
        return key != null && METRIC_BOUNDARY_KEYS.contains(key);
    }

    static Inspection inspect(Connection connection) throws SQLException
    {
        Set<String> damagedTables = new LinkedHashSet<>();
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check"))
        {
            while(rows.next())
            {
                String table = rows.getString(1);
                if(isReproducibleTable(table))
                {
                    damagedTables.add(table);
                }
            }
        }

        for(String table: REPRODUCIBLE_TABLES)
        {
            if(!tableChecksAreValid(connection, table))
            {
                damagedTables.add(table);
            }
        }

        for(String table: REPRODUCIBLE_AUTOINCREMENT_TABLES)
        {
            if(invalidAutoincrementState(connection, table))
            {
                damagedTables.add(table);
            }
        }

        List<String> invalidMetricBoundaryKeys = new ArrayList<>();
        for(String key: METRIC_BOUNDARY_KEYS)
        {
            if(invalidPositiveMetadata(connection, key))
            {
                invalidMetricBoundaryKeys.add(key);
            }
        }

        long resetRows = damagedTables.isEmpty() ? 0 :
            LegacyActivityReset.count(connection, REPRODUCIBLE_TABLES);
        return new Inspection(damagedTables.size(), resetRows, invalidMetricBoundaryKeys);
    }

    static Inspection repair(Connection connection) throws SQLException
    {
        Inspection before = inspect(connection);
        if(!before.requiresRepair())
        {
            return before;
        }

        if(before.damagedTables() > 0)
        {
            LegacyActivityReset.clear(connection, REPRODUCIBLE_TABLES);
            restartMetricBoundaries(connection, METRIC_BOUNDARY_KEYS);
        }
        else
        {
            restartMetricBoundaries(connection, before.invalidMetricBoundaryKeys());
        }

        Inspection remaining = inspect(connection);
        if(remaining.requiresRepair())
        {
            throw new SQLException("Current receiver-derived state reset did not remove every damaged row");
        }
        return before;
    }

    static DatabaseMigrationChain.StepPreflight preflight(Inspection inspection)
    {
        return new DatabaseMigrationChain.StepPreflight(STEP_ID,
            "Reset damaged reproducible receiver activity and statistics state",
            DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.CURRENT_VERSION,
            effects(inspection));
    }

    static List<DatabaseMigrationEffect> effects(Inspection inspection)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "bounded receiver activity and statistics rows", inspection.resetRows(),
                "Discard the damaged receiver-derived component and its identity high-water marks while preserving " +
                    "administrator configuration"),
            metricBoundaryEffect(inspection));
    }

    private static DatabaseMigrationEffect metricBoundaryEffect(Inspection inspection)
    {
        long affectedRows = inspection.damagedTables() > 0 ? METRIC_BOUNDARY_KEYS.size() :
            inspection.invalidMetricBoundaryKeys().size();
        return new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
            "receiver metric collection boundaries", affectedRows,
            inspection.damagedTables() > 0 ?
                "Restart all collection windows after discarding damaged receiver-derived state" :
                "Restart only missing or invalid receiver metric collection boundaries");
    }

    private static boolean invalidPositiveMetadata(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            """
                SELECT CASE WHEN typeof(value)='text' AND length(CAST(value AS BLOB)) <= 64 THEN value END,
                       updated_at_ms, typeof(value), typeof(updated_at_ms)
                FROM database_metadata WHERE key=?
                """))
        {
            statement.setString(1, key);
            try(ResultSet rows = statement.executeQuery())
            {
                if(!rows.next())
                {
                    return true;
                }
                String value = rows.getString(1);
                if(value == null || !"text".equals(rows.getString(3)) ||
                    !"integer".equals(rows.getString(4)) || rows.getLong(2) <= 0)
                {
                    return true;
                }
                try
                {
                    return Long.parseLong(value) <= 0;
                }
                catch(NumberFormatException ignored)
                {
                    return true;
                }
            }
        }
    }

    private static boolean tableChecksAreValid(Connection connection, String table) throws SQLException
    {
        if(!table.matches("[A-Za-z_][A-Za-z0-9_]*"))
        {
            throw new SQLException("Unsafe SQLite identifier in current derived-state repair: " + table);
        }

        boolean result = false;
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("PRAGMA quick_check('" + table + "')"))
        {
            while(rows.next())
            {
                result = true;
                if(!"ok".equalsIgnoreCase(rows.getString(1)))
                {
                    return false;
                }
            }
        }
        return result;
    }

    /**
     * Detects allocator state that can make the next receiver-produced insert fail or behave ambiguously.  Missing
     * sequence state is valid for an empty table; a retained table must have exactly one non-negative, incrementable
     * integer high-water mark at or above its largest row identity.
     */
    private static boolean invalidAutoincrementState(Connection connection, String table) throws SQLException
    {
        if(!REPRODUCIBLE_AUTOINCREMENT_TABLES.contains(table))
        {
            throw new SQLException("Unexpected receiver-derived AUTOINCREMENT table: " + table);
        }

        long retainedMaximum;
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT coalesce(max(id), 0) FROM " + table))
        {
            if(!rows.next())
            {
                throw new SQLException("Unable to inspect receiver-derived identity state for " + table);
            }
            retainedMaximum = rows.getLong(1);
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) AS source_rows,
                   coalesce(sum(CASE WHEN typeof(seq)='integer' AND seq >= ? AND seq >= 0
                                          AND seq <= ?
                                     THEN 1 ELSE 0 END), 0) AS valid_rows
            FROM sqlite_sequence
            WHERE typeof(name)='text' AND name=?
            """))
        {
            statement.setLong(1, retainedMaximum);
            statement.setLong(2, Long.MAX_VALUE - 2);
            statement.setString(3, table);
            try(ResultSet rows = statement.executeQuery())
            {
                if(!rows.next())
                {
                    throw new SQLException("Unable to inspect receiver-derived identity state for " + table);
                }
                long sourceRows = rows.getLong("source_rows");
                long validRows = rows.getLong("valid_rows");
                return sourceRows > 1 || sourceRows == 1 && validRows != 1 ||
                    sourceRows == 0 && retainedMaximum > 0;
            }
        }
    }

    private static void restartMetricBoundaries(Connection connection, List<String> keys) throws SQLException
    {
        long now = Math.max(1L, System.currentTimeMillis());
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO database_metadata(key, value, updated_at_ms)
            VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at_ms=excluded.updated_at_ms
            """))
        {
            for(String key: keys)
            {
                statement.setString(1, key);
                statement.setString(2, Long.toString(now));
                statement.setLong(3, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    record Inspection(int damagedTables, long resetRows, List<String> invalidMetricBoundaryKeys)
    {
        Inspection
        {
            if(damagedTables < 0 || resetRows < 0)
            {
                throw new IllegalArgumentException("Current derived-state repair counts must not be negative");
            }
            invalidMetricBoundaryKeys = List.copyOf(invalidMetricBoundaryKeys);
        }

        static Inspection none()
        {
            return new Inspection(0, 0, List.of());
        }

        boolean requiresRepair()
        {
            return damagedTables > 0 || !invalidMetricBoundaryKeys.isEmpty();
        }
    }
}
