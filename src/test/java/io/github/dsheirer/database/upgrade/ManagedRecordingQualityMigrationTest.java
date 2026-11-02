/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.record.managed.ManagedRecordingSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingQualityMigrationTest
{
    @TempDir Path directory;
    private static final List<String> QUALITY_COLUMNS = List.of("voice_decoded_frames", "voice_repeated_frames",
        "voice_concealed_frames", "voice_missing_frames", "voice_fec_errors", "voice_fec_protected_bits");

    @Test
    void frozenPopulatedElevenPreservesEveryFactAndLeavesHistoricalQualityUnknown() throws Exception
    {
        Path catalog = fixture(directory.resolve("eleven.sqlite"));
        Map<String,List<String>> before;
        Map<String,String> beforeSchema;
        List<String> beforeColumns;
        try(Connection connection = open(catalog))
        {
            before = contents(connection);
            beforeSchema = schema(connection);
            beforeColumns = columns(connection);
        }
        byte[] original = Files.readAllBytes(catalog);
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspectForStartup(catalog).state());
        assertThrows(SQLException.class, () -> new ManagedRecordingCatalog(catalog, directory));
        assertArrayEquals(original, Files.readAllBytes(catalog), "Runtime startup must not upgrade the catalog");

        var result = ManagedRecordingCatalogMigrator.migrate(catalog, true);
        assertTrue(result.migrated());
        assertNotNull(result.backup());
        try(Connection connection = open(catalog))
        {
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            assertEquals(before, contents(connection));
            var expectedColumns = new ArrayList<>(beforeColumns);
            expectedColumns.addAll(QUALITY_COLUMNS);
            assertEquals(expectedColumns, columns(connection), "Only six optional quality columns are added");
            var afterSchema = schema(connection);
            assertEquals(beforeSchema.keySet(), afterSchema.keySet(), "No table, index or trigger is added");
            beforeSchema.forEach((name, ddl) -> {
                if(!name.equals("recording_call")) assertEquals(ddl, afterSchema.get(name), name);
            });
            assertEquals(3, scalar(connection, "SELECT count(*) FROM recording_source"));
            assertEquals(9000, scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='recording_call'"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM recording_transcript_fts " +
                "WHERE recording_transcript_fts MATCH 'verbatim'"));
            integrity(connection);
        }
        try(Connection connection = open(result.backup()))
        {
            ManagedRecordingSchema.validate(connection, 11);
            assertEquals(before, contents(connection));
            assertEquals(beforeSchema, schema(connection));
        }
        Path retry = directory.resolve("retry-copy.sqlite");
        Files.copy(result.backup(), retry);
        assertTrue(ManagedRecordingCatalogMigrator.migrate(retry, false).migrated());
        try(Connection connection = open(retry))
        {
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            assertEquals(before, contents(connection));
            integrity(connection);
        }
        byte[] current = Files.readAllBytes(retry);
        assertFalse(ManagedRecordingCatalogMigrator.migrate(retry, false).migrated());
        assertArrayEquals(current, Files.readAllBytes(retry), "A current catalog is a no-op");
    }

    @Test
    void failureRollsBackAllCheckedColumnsAndPermitsExactRetry() throws Exception
    {
        for(boolean backup: List.of(false, true))
        {
            Path catalog = fixture(directory.resolve("rollback-" + backup + ".sqlite"));
            Map<String,List<String>> before;
            try(Connection connection = open(catalog)) { before = contents(connection); }
            byte[] original = Files.readAllBytes(catalog);
            assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, backup,
                connection -> {
                    ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
                    assertTrue(columns(connection).containsAll(QUALITY_COLUMNS));
                    throw new SQLException("Injected quality final validation failure");
                }, ManagedRecordingQualityMigrationTest::open));
            assertArrayEquals(original, Files.readAllBytes(catalog));
            try(Connection connection = open(catalog))
            {
                ManagedRecordingSchema.validate(connection, 11);
                assertEquals(before, contents(connection));
                assertTrue(Collections.disjoint(columns(connection), QUALITY_COLUMNS));
                integrity(connection);
            }
            assertTrue(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
            try(Connection connection = open(catalog))
            {
                ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
                assertEquals(before, contents(connection));
                integrity(connection);
            }
        }
    }

    @Test
    void freshAndMigratedCatalogsShareExactSchemaAndEnforceIndependentKnownGroups() throws Exception
    {
        Path migrated = fixture(directory.resolve("constraints.sqlite"));
        ManagedRecordingCatalogMigrator.migrate(migrated, false);
        Path fresh = directory.resolve("fresh.sqlite");
        try(Connection connection = open(fresh); var statement = connection.createStatement())
        {
            for(String ddl: ManagedRecordingSchema.creationStatements()) statement.execute(ddl);
            statement.executeUpdate("INSERT INTO catalog_metadata VALUES(1," +
                ManagedRecordingSchema.CURRENT_FORMAT_VERSION + ",0,0)");
            statement.execute("PRAGMA application_id=" + ManagedRecordingSchema.APPLICATION_ID);
            statement.execute("PRAGMA user_version=" + ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,protocol,call_type,voice_type) VALUES(41,1000,2000,1000,'fresh.mp3',64,1,1,1)");
            statement.executeUpdate("UPDATE catalog_metadata SET call_count=1,total_bytes=64 WHERE id=1");
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
        }
        try(Connection left = open(migrated); Connection right = open(fresh))
        { assertEquals(schema(left), schema(right)); }
        for(Path catalog: List.of(migrated, fresh))
        {
            try(Connection connection = open(catalog))
            {
                for(int mask = 1; mask < 15; mask++)
                {
                    Object[] values = new Object[6];
                    for(int index = 0; index < 4; index++) values[index] = (mask & (1 << index)) != 0 ? 1L : null;
                    assertThrows(SQLException.class, () -> quality(connection, values), "Partial frame group: " + mask);
                }
                assertThrows(SQLException.class, () -> quality(connection, 0L, 0L, 0L, 10L, null, null),
                    "Missing frames alone do not establish observed voice quality");
                assertThrows(SQLException.class, () -> quality(connection, null, null, null, null, 0L, null));
                assertThrows(SQLException.class, () -> quality(connection, null, null, null, null, null, 100L));
                assertThrows(SQLException.class, () -> quality(connection, null, null, null, null, 0L, 0L));
                for(int index = 0; index < 6; index++)
                {
                    Object[] negative = {1L, 0L, 0L, 0L, 0L, 100L};
                    negative[index] = -1L;
                    assertThrows(SQLException.class, () -> quality(connection, negative));
                    Object[] fractional = {1L, 0L, 0L, 0L, 0L, 100L};
                    fractional[index] = 1.5;
                    assertThrows(SQLException.class, () -> quality(connection, fractional));
                }
                quality(connection, 5L, 0L, 0L, 0L, 0L, 100L);
                assertEquals(0, scalar(connection, "SELECT voice_fec_errors FROM recording_call WHERE id=41"));
                assertEquals(0, scalar(connection, "SELECT voice_missing_frames FROM recording_call WHERE id=41"));
                quality(connection, 0L, 1L, 0L, 0L, null, null);
                quality(connection, 0L, 0L, 1L, 3L, null, null);
                quality(connection, null, null, null, null, 0L, 100L);
                quality(connection, null, null, null, null, null, null);
                assertEquals(0, scalar(connection, "SELECT count(*) FROM recording_call WHERE " +
                    QUALITY_COLUMNS.stream().map(column -> column + " IS NOT NULL").collect(
                        java.util.stream.Collectors.joining(" OR "))));
                integrity(connection);
            }
        }
    }

    @Test
    void refusesMixedOrNewerCatalogsWithoutMutation() throws Exception
    {
        for(boolean newer: List.of(false, true))
        {
            Path catalog = fixture(directory.resolve("refused-" + newer + ".sqlite"));
            try(Connection connection = open(catalog); var statement = connection.createStatement())
            {
                statement.execute(newer ? "PRAGMA user_version=" + (ManagedRecordingSchema.CURRENT_FORMAT_VERSION + 1) :
                    "ALTER TABLE recording_call ADD COLUMN voice_decoded_frames INTEGER");
            }
            byte[] before = Files.readAllBytes(catalog);
            assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.inspectForStartup(catalog));
            assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, false));
            assertArrayEquals(before, Files.readAllBytes(catalog));
        }
    }

    private static void quality(Connection connection, Object... values) throws SQLException
    {
        try(var statement = connection.prepareStatement("UPDATE recording_call SET " +
            QUALITY_COLUMNS.stream().map(column -> column + "=?").collect(java.util.stream.Collectors.joining(",")) +
            " WHERE id=41"))
        {
            assertEquals(6, values.length);
            for(int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static Path fixture(Path path) throws Exception
    {
        byte[] frozen;
        try(var input = ManagedRecordingQualityMigrationTest.class.getResourceAsStream(
            "/database/managed-recordings-format-11.sql"))
        { assertNotNull(input); frozen = input.readAllBytes(); }
        assertEquals("15a5245c5dc9ffc06ef8a4103a8d21184f0ab15a9f856b8ef910a7fd662bc99b",
            HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(frozen)));
        try(Connection connection = open(path); var statement = connection.createStatement())
        {
            for(String sql: new String(frozen, StandardCharsets.UTF_8).lines().toList()) statement.execute(sql);
            statement.execute("ANALYZE");
            ManagedRecordingSchema.validate(connection, 11);
            integrity(connection);
        }
        return path;
    }

    private static Map<String,List<String>> contents(Connection connection) throws SQLException
    {
        var result = new LinkedHashMap<String,List<String>>();
        var tables = new ArrayList<String>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        { while(rows.next()) tables.add(rows.getString(1)); }
        for(String table: tables)
        {
            var values = new ArrayList<String>();
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM \"" + table + "\""))
            {
                while(rows.next())
                {
                    var row = new ArrayList<String>();
                    for(int column = 1; column <= rows.getMetaData().getColumnCount(); column++)
                    {
                        if(ManagedRecordingMigrationFacts.isUnmeasuredQualityColumn(table, rows, column)) continue;
                        if(table.equals("catalog_metadata") && column == 2) continue;
                        Object value = rows.getObject(column);
                        row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : String.valueOf(value));
                    }
                    values.add(row.toString());
                }
            }
            Collections.sort(values);
            result.put(table, values);
        }
        return result;
    }

    private static List<String> columns(Connection connection) throws SQLException
    {
        var result = new ArrayList<String>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_info(recording_call)"))
        { while(rows.next()) result.add(rows.getString("name")); }
        return result;
    }

    private static Map<String,String> schema(Connection connection) throws SQLException
    {
        var result = new LinkedHashMap<String,String>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT name,sql FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*' ORDER BY name"))
        { while(rows.next()) result.put(rows.getString(1), rows.getString(2)); }
        return result;
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    { try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
      { assertTrue(rows.next()); return rows.getLong(1); } }

    private static void integrity(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_check"))
        { assertFalse(rows.next()); }
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA integrity_check"))
        { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); assertFalse(rows.next()); }
    }

    private static Connection open(Path path) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try(var statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); }
        return connection;
    }
}
