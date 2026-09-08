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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repairs SQLite allocator state before an adjacent migration needs to create rows.
 *
 * <p>The JavaScript-facing configuration APIs require exact integer identities.  SQLite accepts larger signed
 * integers and also permits malformed or duplicate rows in its internal {@code sqlite_sequence} table.  Neither is
 * structural database damage: unusable configuration rows can be isolated, and an allocator row can be rebuilt from
 * the highest retained identity without blocking every other migration component.</p>
 */
final class SqliteIdentityRepair
{
    static final String STEP_ID = "repair-sqlite-identities";
    static final long JSON_SAFE_INTEGER_MAXIMUM = (1L << 53) - 1L;
    private static final int MAXIMUM_SCHEMA_NAME_BYTES = 128;
    private static final Set<String> CONFIGURATION_TABLES = Set.of(
        "alias_list", "alias", "scan_list", "alias_broadcast_channel",
        "alias_list_unmatched_talkgroup_stream", "configuration_channel",
        "configuration_broadcast_stream", "web_user");

    private SqliteIdentityRepair()
    {
    }

    static Inspection inspect(Connection connection) throws SQLException
    {
        return analyze(connection).inspection();
    }

    static Inspection repair(Connection connection) throws SQLException
    {
        Analysis analysis = analyze(connection);
        Inspection before = analysis.inspection();
        if(!before.requiresRepair())
        {
            return before;
        }

        for(TableInspection table: analysis.tables().values())
        {
            if(table.unsafeConfigurationRows() > 0)
            {
                try(Statement statement = connection.createStatement())
                {
                    statement.executeUpdate("DELETE FROM " + identifier(table.name()) + " WHERE id <= 0 OR id >= " +
                        JSON_SAFE_INTEGER_MAXIMUM);
                }
            }
        }

        if(analysis.orphanedSequenceRows() > 0)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM sqlite_sequence
                WHERE typeof(name) <> 'text'
                   OR length(CAST(name AS BLOB)) > ?
                   OR NOT EXISTS (
                       SELECT 1 FROM sqlite_schema target
                       WHERE target.type='table' AND target.name=sqlite_sequence.name
                         AND instr(upper(coalesce(target.sql, '')), 'AUTOINCREMENT') > 0
                   )
                """))
            {
                statement.setInt(1, MAXIMUM_SCHEMA_NAME_BYTES);
                statement.executeUpdate();
            }
        }

        try(PreparedStatement delete = connection.prepareStatement("DELETE FROM sqlite_sequence WHERE name=?");
            PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO sqlite_sequence(name, seq) VALUES (?, ?)"))
        {
            for(TableInspection table: analysis.tables().values())
            {
                if(!table.sequenceRepairRequired())
                {
                    continue;
                }
                delete.setString(1, table.name());
                delete.executeUpdate();

                long retainedMaximum = maximumRetainedId(connection, table.name());
                if(retainedMaximum > 0)
                {
                    insert.setString(1, table.name());
                    insert.setLong(2, retainedMaximum);
                    insert.executeUpdate();
                }
            }
        }

        Inspection remaining = inspect(connection);
        if(remaining.requiresRepair())
        {
            throw new SQLException("SQLite identity repair did not produce usable allocator state");
        }
        return before;
    }

    static DatabaseMigrationChain.StepPreflight preflight(Inspection inspection, int version)
    {
        return new DatabaseMigrationChain.StepPreflight(STEP_ID,
            "Normalize recoverable SQLite configuration identities and allocator state",
            version, version, effects(inspection));
    }

    static List<DatabaseMigrationEffect> effects(Inspection inspection)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "configuration rows at or beyond the exhausted JSON-safe identity boundary",
                inspection.droppedConfigurationRows(),
                "Discard only configuration rows whose identity leaves no exactly representable successor for clients"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "SQLite identity high-water marks", inspection.repairedSequenceRows(),
                "Rebuild only malformed, duplicate, exhausted, missing, or stale allocator rows from retained identities"));
    }

    private static Analysis analyze(Connection connection) throws SQLException
    {
        Set<String> autoIncrementTables = autoIncrementTables(connection);
        Map<String,TableInspection> tables = new LinkedHashMap<>();
        long droppedRows = 0;
        long repairedSequences = 0;

        for(String table: autoIncrementTables)
        {
            long unsafeRows = unsafeConfigurationRows(connection, table);
            long retainedMaximum = maximumRetainedId(connection, table);
            SequenceState sequence = inspectSequence(connection, table, retainedMaximum);
            tables.put(table, new TableInspection(table, unsafeRows, sequence.repairRequired()));
            droppedRows = Math.addExact(droppedRows, unsafeRows);
            repairedSequences = Math.addExact(repairedSequences, sequence.affectedRows());
        }

        long orphanedSequences = orphanedSequenceRows(connection);
        repairedSequences = Math.addExact(repairedSequences, orphanedSequences);
        return new Analysis(new Inspection(droppedRows, repairedSequences), Map.copyOf(tables), orphanedSequences);
    }

    private static Set<String> autoIncrementTables(Connection connection) throws SQLException
    {
        Set<String> tables = new LinkedHashSet<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB)) <= ? THEN name END
            FROM sqlite_schema
            WHERE type='table' AND instr(upper(coalesce(sql, '')), 'AUTOINCREMENT') > 0
            ORDER BY name
            """))
        {
            statement.setInt(1, MAXIMUM_SCHEMA_NAME_BYTES);
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next())
                {
                    String table = rows.getString(1);
                    if(table == null)
                    {
                        throw new SQLException("AUTOINCREMENT table has an unusable schema name");
                    }
                    identifier(table);
                    if(CONFIGURATION_TABLES.contains(table))
                    {
                        tables.add(table);
                    }
                }
            }
        }
        return Set.copyOf(tables);
    }

    private static long unsafeConfigurationRows(Connection connection, String table) throws SQLException
    {
        return scalar(connection, "SELECT COUNT(*) FROM " + identifier(table) +
            " WHERE typeof(id) <> 'integer' OR id <= 0 OR id >= " + JSON_SAFE_INTEGER_MAXIMUM);
    }

    private static long maximumRetainedId(Connection connection, String table) throws SQLException
    {
        return scalar(connection, "SELECT coalesce(max(id), 0) FROM " + identifier(table) +
            " WHERE typeof(id)='integer' AND id > 0 AND id < " + JSON_SAFE_INTEGER_MAXIMUM);
    }

    private static SequenceState inspectSequence(Connection connection, String table, long retainedMaximum)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) AS source_rows,
                   coalesce(sum(CASE WHEN typeof(seq)='integer' AND seq >= ? AND seq >= 0
                                          AND (seq <= ? OR (? = ? AND seq = ?))
                                     THEN 1 ELSE 0 END), 0) AS valid_rows
            FROM sqlite_sequence
            WHERE typeof(name)='text' AND name=?
            """))
        {
            statement.setLong(1, retainedMaximum);
            statement.setLong(2, JSON_SAFE_INTEGER_MAXIMUM - 2);
            statement.setLong(3, retainedMaximum);
            statement.setLong(4, JSON_SAFE_INTEGER_MAXIMUM - 1);
            statement.setLong(5, JSON_SAFE_INTEGER_MAXIMUM - 1);
            statement.setString(6, table);
            try(ResultSet rows = statement.executeQuery())
            {
                if(!rows.next())
                {
                    throw new SQLException("Unable to inspect SQLite identity state for " + table);
                }
                long sourceRows = rows.getLong("source_rows");
                long validRows = rows.getLong("valid_rows");
                boolean repair = sourceRows > 1 || sourceRows == 1 && validRows != 1 ||
                    sourceRows == 0 && retainedMaximum > 0;
                return new SequenceState(repair, repair ? Math.max(1, sourceRows) : 0);
            }
        }
    }

    private static long orphanedSequenceRows(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) FROM sqlite_sequence source
            WHERE typeof(source.name) <> 'text'
               OR length(CAST(source.name AS BLOB)) > ?
               OR NOT EXISTS (
                   SELECT 1 FROM sqlite_schema target
                   WHERE target.type='table' AND target.name=source.name
                     AND instr(upper(coalesce(target.sql, '')), 'AUTOINCREMENT') > 0
               )
            """))
        {
            statement.setInt(1, MAXIMUM_SCHEMA_NAME_BYTES);
            try(ResultSet rows = statement.executeQuery())
            {
                if(!rows.next())
                {
                    throw new SQLException("Unable to inspect orphaned SQLite identity state");
                }
                return rows.getLong(1);
            }
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            if(!rows.next())
            {
                throw new SQLException("SQLite identity query returned no result");
            }
            return rows.getLong(1);
        }
    }

    private static String identifier(String value) throws SQLException
    {
        if(value == null || !value.matches("[a-z][a-z0-9_]*"))
        {
            throw new SQLException("Unsafe SQLite identity table name");
        }
        return value;
    }

    record Inspection(long droppedConfigurationRows, long repairedSequenceRows)
    {
        static Inspection none()
        {
            return new Inspection(0, 0);
        }

        boolean requiresRepair()
        {
            return droppedConfigurationRows > 0 || repairedSequenceRows > 0;
        }
    }

    private record TableInspection(String name, long unsafeConfigurationRows, boolean sequenceRepairRequired)
    {
    }

    private record SequenceState(boolean repairRequired, long affectedRows)
    {
    }

    private record Analysis(Inspection inspection, Map<String,TableInspection> tables, long orphanedSequenceRows)
    {
    }
}
