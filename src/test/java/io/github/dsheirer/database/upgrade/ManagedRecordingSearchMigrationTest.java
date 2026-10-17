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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingSearchMigrationTest
{
    @TempDir Path directory;

    @Test
    void frozenPopulatedFivePreservesEveryValueAndAllocatorAndRetriesFromItsBackup() throws Exception
    {
        Path catalog = fixture(directory.resolve("prior.sqlite"));
        Map<String,List<String>> before;
        try(Connection connection = open(catalog))
        {
            ManagedRecordingSchema.validate(connection, 5);
            before = contents(connection);
        }
        byte[] original = Files.readAllBytes(catalog);
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspectForStartup(catalog).state());
        assertThrows(SQLException.class, () -> new ManagedRecordingCatalog(catalog, directory));
        assertArrayEquals(original, Files.readAllBytes(catalog), "Runtime validates but never upgrades the catalog");

        var migrated = ManagedRecordingCatalogMigrator.migrate(catalog);
        assertTrue(migrated.migrated());
        assertNotNull(migrated.backup());
        Map<String,String> signature;
        try(Connection connection = open(catalog))
        {
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            assertEquals(before, contents(connection));
            signature = schema(connection);
            assertEquals(9000, scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='recording_call'"));
            assertEquals(3, scalar(connection, "SELECT count(*) FROM recording_tone"));
            integrity(connection);
        }
        try(Connection backup = open(migrated.backup()))
        {
            ManagedRecordingSchema.validate(backup, 5);
            assertEquals(before, contents(backup));
        }
        Path retry = directory.resolve("retry.sqlite");
        Files.copy(migrated.backup(), retry);
        assertTrue(ManagedRecordingCatalogMigrator.migrate(retry, false).migrated());
        try(Connection connection = open(retry))
        {
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            assertEquals(before, contents(connection));
            assertEquals(signature, schema(connection));
            integrity(connection);
        }
        Path fresh = directory.resolve("fresh.sqlite");
        try(Connection connection = open(fresh); var statement = connection.createStatement())
        {
            for(String ddl: ManagedRecordingSchema.creationStatements()) statement.execute(ddl);
            statement.executeUpdate("INSERT INTO catalog_metadata VALUES(1," + ManagedRecordingSchema.CURRENT_FORMAT_VERSION + ",0,0)");
            statement.execute("PRAGMA application_id=" + ManagedRecordingSchema.APPLICATION_ID);
            statement.execute("PRAGMA user_version=" + ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            assertEquals(signature, schema(connection), "Fresh and migrated formats have one exact schema");
        }
        assertFalse(ManagedRecordingCatalogMigrator.migrate(catalog).migrated());
    }

    @Test
    void failedIndexUpdateRollsBackTheWholeSourceAndAllowsRetry() throws Exception
    {
        for(boolean backup: List.of(false, true))
        {
            Path folder = Files.createDirectory(directory.resolve("failure-" + backup));
            Path catalog = fixture(folder.resolve("catalog.sqlite"));
            Map<String,List<String>> before;
            Map<String,String> oldSignature;
            try(Connection connection = open(catalog))
            {
                before = contents(connection);
                oldSignature = schema(connection);
            }
            assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, backup,
                connection -> { throw new SQLException("Injected search-index final validation failure"); },
                ManagedRecordingSearchMigrationTest::open));
            try(Connection connection = open(catalog))
            {
                ManagedRecordingSchema.validate(connection, 5);
                assertEquals(oldSignature, schema(connection));
                assertEquals(before, contents(connection));
                integrity(connection);
            }
            if(backup)
            {
                try(var paths = Files.list(folder.resolve("backups")))
                {
                    var retained = paths.toList();
                    assertEquals(1, retained.size());
                    try(Connection connection = open(retained.getFirst()))
                    {
                        ManagedRecordingSchema.validate(connection, 5);
                        assertEquals(before, contents(connection));
                    }
                }
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
    void qualifiedIdentityAndCategoricalQueriesSeekInPageOrder() throws Exception
    {
        Path catalog = fixture(directory.resolve("plans.sqlite"));
        ManagedRecordingCatalogMigrator.migrate(catalog, false);
        try(Connection connection = open(catalog))
        {
            for(String prefix: List.of("source", "target"))
            {
                String index = "idx_recording_call_" + prefix + "_home_time";
                String sql = "SELECT id,start_ms FROM recording_call INDEXED BY " + index +
                    " WHERE " + prefix + "_home_wacn=781824 AND " + prefix + "_home_system=291 AND " +
                    prefix + "_home_id=7001 AND start_ms BETWEEN 0 AND 6000 ORDER BY start_ms DESC,id DESC LIMIT 26";
                assertSeek(connection, sql, index);
            }
            assertSeek(connection, "SELECT call_id,start_ms FROM recording_patch_member INDEXED BY " +
                "idx_recording_patch_member_home_lookup WHERE kind=2 AND home_wacn=781824 AND home_system=291 " +
                "AND home_id=7001 AND start_ms BETWEEN 0 AND 6000 ORDER BY start_ms DESC,call_id DESC LIMIT 26",
                "idx_recording_patch_member_home_lookup");
            for(String column: List.of("protocol", "call_type", "voice_type"))
            {
                String index = "idx_recording_call_" + column + "_time";
                assertSeek(connection, "SELECT id,start_ms FROM recording_call INDEXED BY " + index +
                    " WHERE " + column + "=1 AND start_ms BETWEEN 0 AND 6000 ORDER BY start_ms DESC,id DESC LIMIT 26", index);
            }
        }
    }

    @Test
    void mixedAndNewerLayoutsAreRefusedWithoutMutation() throws Exception
    {
        for(boolean newer: List.of(false, true))
        {
            Path catalog = fixture(directory.resolve("refuse-" + newer + ".sqlite"));
            try(Connection connection = open(catalog); var statement = connection.createStatement())
            {
                statement.execute(newer ? "PRAGMA user_version=" + (ManagedRecordingSchema.CURRENT_FORMAT_VERSION + 1) :
                    "CREATE INDEX unexpected_source_index ON recording_call(source_id)");
            }
            byte[] before = Files.readAllBytes(catalog);
            assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.inspect(catalog));
            assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, false));
            assertArrayEquals(before, Files.readAllBytes(catalog));
        }
    }

    private static Path fixture(Path path) throws Exception
    {
        byte[] frozen;
        try(var input = ManagedRecordingSearchMigrationTest.class.getResourceAsStream(
            "/database/managed-recordings-format-5.sql"))
        {
            assertNotNull(input);
            frozen = input.readAllBytes();
        }
        assertEquals("4a52aebf2a1b1e2fac36563618c89cea76238602e350c02deb2abdcc246e704a",
            java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(frozen)),
            "The populated prior format is independent of current schema creation");
        try(Connection connection = open(path); var statement = connection.createStatement())
        {
            for(String sql: new String(frozen, StandardCharsets.UTF_8).lines().toList()) statement.execute(sql);
            ManagedRecordingSchema.validate(connection, 5);
            integrity(connection);
        }
        return path;
    }

    private static void assertSeek(Connection connection, String query, String index) throws SQLException
    {
        var plans = new ArrayList<String>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("EXPLAIN QUERY PLAN " + query))
        {
            while(rows.next()) plans.add(rows.getString(4));
        }
        assertTrue(plans.stream().anyMatch(plan -> plan.startsWith("SEARCH ") && plan.contains(index)), plans.toString());
        assertFalse(plans.stream().anyMatch(plan -> plan.contains("TEMP B-TREE")), plans.toString());
    }

    private static Map<String,List<String>> contents(Connection connection) throws SQLException
    {
        var result = new LinkedHashMap<String,List<String>>();
        var tables = new ArrayList<String>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' AND name NOT GLOB 'recording_transcript_fts*' ORDER BY name"))
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
                        if(!table.equals("catalog_metadata") || column != 2) row.add(String.valueOf(rows.getObject(column)));
                    values.add(row.toString());
                }
            }
            Collections.sort(values);
            result.put(table, values);
        }
        // A forward update adds the empty child; every pre-existing fact still compares exactly.
        result.putIfAbsent("recording_identifier", List.of());
        result.putIfAbsent("recording_source", List.of());
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

    private static long scalar(Connection connection, String query) throws SQLException
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(query))
        { assertTrue(rows.next()); return rows.getLong(1); }
    }

    private static void integrity(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_check"))
        { assertFalse(rows.next()); }
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA quick_check"))
        { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
    }

    private static Connection open(Path path) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try(var statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); }
        return connection;
    }
}
