/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** Preserve complete Activity facts while allowing only declared full-chain changes. */
final class ActivityMigrationPreservation
{
    static final Set<String> FORMAT_73_INDEXES = Set.of(
        "idx_receiver_activity_event_retention", "idx_receiver_activity_event_system_time",
        "idx_receiver_activity_event_system_action_time", "idx_receiver_activity_event_system_event_type_time",
        "idx_receiver_activity_event_channel_event_type_time", "idx_receiver_activity_event_channel_source_id_time",
        "idx_receiver_activity_event_channel_target_id_time", "idx_receiver_activity_event_system_encrypted_time",
        "idx_receiver_activity_event_channel_encrypted_time");
    private static final Set<String> FORMAT_69_INDEXES = Set.of("idx_receiver_activity_event_encryption",
        "idx_receiver_activity_event_system_encrypted_time", "idx_receiver_activity_event_channel_encrypted_time");
    private ActivityMigrationPreservation() {}

    static boolean rebuiltIndexStatistic(String table, ResultSet rows, Set<String> indexes) throws SQLException
    {
        if(!table.equals("sqlite_stat1") && !table.equals("sqlite_stat4")) return false;
        String index = rows.getString("idx");
        return index != null && indexes.contains(index);
    }

    static boolean format73Statistic(String table, ResultSet rows) throws SQLException
    {
        return rebuiltIndexStatistic(table, rows, FORMAT_73_INDEXES);
    }

    static Map<String,List<String>> capture(Path configuration) throws Exception
    { return capture(configuration, true); }
    static Map<String,List<String>> original(Path configuration) throws Exception
    { return capture(configuration, false); }
    private static Map<String,List<String>> capture(Path configuration, boolean currentProjection) throws Exception
    {
        Map<String,List<String>> result = new TreeMap<>();
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + ActivityDatabasePath.forConfiguration(configuration)))
        {
            List<String> tables = new ArrayList<>();
            try(var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
            { while(rows.next()) tables.add(rows.getString(1)); }
            for(String table: tables)
            {
                boolean plannerStatistics = table.equals("sqlite_stat1") || table.equals("sqlite_stat4");
                List<String> values = new ArrayList<>();
                try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM \"" + table + "\""))
                {
                    while(rows.next())
                    {
                        if(currentProjection && table.equals("database_metadata") &&
                            rows.getString("key").equals("database_format_version")) continue;
                        if(currentProjection && (rebuiltIndexStatistic(table, rows, FORMAT_69_INDEXES) ||
                            format73Statistic(table, rows))) continue;
                        StringBuilder value = new StringBuilder();
                        for(int column=1; column<=rows.getMetaData().getColumnCount(); column++)
                        {
                            Object field = rows.getObject(column);
                            if(currentProjection && table.equals("receiver_activity_event") &&
                                rows.getMetaData().getColumnName(column).equals("encrypted"))
                                field = rows.getInt(column) == 1 || Set.of(6,8,10,17,20,26).contains(
                                    rows.getInt("event_type_code")) ? 2 : 0;
                            String text = field == null ? "null" : field instanceof byte[] bytes ?
                                "bytes:" + HexFormat.of().formatHex(bytes) : field.getClass().getSimpleName() + ":" + field;
                            value.append(text.length()).append(':').append(text);
                        }
                        values.add(value.toString());
                    }
                }
                if(currentProjection && plannerStatistics && values.isEmpty()) continue;
                Collections.sort(values); result.put(table, values);
            }
        }
        return result;
    }
    static void assertCurrent(Path configuration, Map<String,List<String>> expected) throws Exception
    {
        ConfigurationHistoryDatabase.validatePair(configuration);
        assertEquals(expected, capture(configuration));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + ActivityDatabasePath.forConfiguration(configuration)))
        {
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA quick_check"))
            { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_check"))
            { assertFalse(rows.next()); }
        }
    }
}

