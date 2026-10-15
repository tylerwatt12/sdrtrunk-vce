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

class ManagedRecordingIdentifierMigrationTest
{
    @TempDir Path directory;

    @Test
    void populatedSixPreservesAllFactsAndAllocatorWithoutGuessingIdentifiers() throws Exception
    {
        Path catalog = fixture(directory.resolve("six.sqlite"));
        Map<String,List<String>> before;
        try(Connection connection = open(catalog)) { before = contents(connection); }
        byte[] original = Files.readAllBytes(catalog);
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspectForStartup(catalog).state());
        assertThrows(SQLException.class, () -> new ManagedRecordingCatalog(catalog, directory));
        assertArrayEquals(original, Files.readAllBytes(catalog));

        var migrated = ManagedRecordingCatalogMigrator.migrate(catalog);
        assertTrue(migrated.migrated());
        Map<String,String> signature;
        try(Connection connection = open(catalog))
        {
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            assertEquals(before, contents(connection));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM recording_identifier"));
            assertEquals(9000, scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='recording_call'"));
            signature = schema(connection);
            integrity(connection);
        }
        try(Connection connection = open(migrated.backup()))
        {
            ManagedRecordingSchema.validate(connection, 6);
            assertEquals(before, contents(connection));
        }
        Path retry = directory.resolve("retry.sqlite");
        Files.copy(migrated.backup(), retry);
        assertTrue(ManagedRecordingCatalogMigrator.migrate(retry, false).migrated());
        try(Connection connection = open(retry))
        {
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            assertEquals(before, contents(connection));
            assertEquals(signature, schema(connection));
        }
        try(Connection connection = open(directory.resolve("fresh.sqlite")); var statement = connection.createStatement())
        {
            for(String ddl: ManagedRecordingSchema.ddlForFormat(ManagedRecordingSchema.CURRENT_FORMAT_VERSION).values())
                statement.execute(ddl);
            statement.executeUpdate("INSERT INTO catalog_metadata VALUES(1," + ManagedRecordingSchema.CURRENT_FORMAT_VERSION + ",0,0)");
            statement.execute("PRAGMA application_id=" + ManagedRecordingSchema.APPLICATION_ID);
            statement.execute("PRAGMA user_version=" + ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            assertEquals(signature, schema(connection));
        }
        assertFalse(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
    }

    @Test
    void failedIdentifierUpdateRollsBackWithEitherBackupChoiceAndAllowsRetry() throws Exception
    {
        for(boolean backup: List.of(false, true))
        {
            Path folder = Files.createDirectory(directory.resolve("failure-" + backup));
            Path catalog = fixture(folder.resolve("catalog.sqlite"));
            Map<String,List<String>> before;
            Map<String,String> oldSignature;
            try(Connection connection = open(catalog))
            { before = contents(connection); oldSignature = schema(connection); }
            assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, backup,
                connection -> { throw new SQLException("Injected identifier final validation failure"); },
                ManagedRecordingIdentifierMigrationTest::open));
            try(Connection connection = open(catalog))
            {
                ManagedRecordingSchema.validate(connection, 6);
                assertEquals(before, contents(connection));
                assertEquals(oldSignature, schema(connection));
                integrity(connection);
            }
            assertTrue(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
            try(Connection connection = open(catalog))
            {
                ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
                assertEquals(before, contents(connection));
            }
        }
    }

    @Test
    void identifierFactsHaveExplicitNamespacesConstraintsCascadeAndIndexedLookups() throws Exception
    {
        Path catalog = fixture(directory.resolve("constraints.sqlite"));
        ManagedRecordingCatalogMigrator.migrate(catalog, false);
        try(Connection connection = open(catalog); var statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO recording_identifier VALUES" +
                "(41,'TAIT1200','radio','source','UNIT-A',1000)," +
                "(41,'MDC1200','radio','destination','123',1000)," +
                "(41,'FLEETSYNC','radio','source','123',1000)," +
                "(41,'DCS','tone','other','D023',1000)," +
                "(41,'LOJACK','function','other','REPLY',1000)");
            assertEquals(5, scalar(connection, "SELECT count(*) FROM recording_identifier"));
            for(String invalid: List.of("'UNKNOWN','radio','source','1',1000",
                "'DCS','radio','source','1',1000", "'TAIT1200','radio','invalid','1',1000",
                "'MDC1200','function','source','1',1000", "'TAIT1200','radio','source','',1000",
                "'TAIT1200','radio','source','   ',1000", "'TAIT1200','radio','source',char(0),1000",
                "'TAIT1200','radio','source','" + "x".repeat(129) + "',1000",
                "'TAIT1200','radio','source','1',-1"))
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO recording_identifier VALUES(41," + invalid + ")"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "INSERT INTO recording_identifier VALUES(999,'TAIT1200','radio','source','UNIT-A',1000)"));
            var plans = new ArrayList<String>();
            try(var rows = statement.executeQuery("EXPLAIN QUERY PLAN SELECT call_id,start_ms FROM " +
                "recording_identifier INDEXED BY idx_recording_identifier_lookup WHERE protocol='TAIT1200' " +
                "AND kind='radio' AND value='UNIT-A' AND start_ms BETWEEN 0 AND 5000 " +
                "ORDER BY start_ms DESC,call_id DESC LIMIT 26"))
            { while(rows.next()) plans.add(rows.getString(4)); }
            assertTrue(plans.stream().anyMatch(plan -> plan.startsWith("SEARCH ") &&
                plan.contains("idx_recording_identifier_lookup")), plans.toString());
            assertFalse(plans.stream().anyMatch(plan -> plan.contains("TEMP B-TREE")), plans.toString());
            statement.executeUpdate("DELETE FROM recording_call WHERE id=41");
            assertEquals(0, scalar(connection, "SELECT count(*) FROM recording_identifier"));
            integrity(connection);
        }
    }

    @Test
    void mixedAndNewerCatalogsRemainUnchangedOnRefusal() throws Exception
    {
        for(boolean newer: List.of(false, true))
        {
            Path catalog = fixture(directory.resolve("refuse-" + newer + ".sqlite"));
            try(Connection connection = open(catalog); var statement = connection.createStatement())
            {
                statement.execute(newer ? "PRAGMA user_version=" + (ManagedRecordingSchema.CURRENT_FORMAT_VERSION + 1) :
                    "CREATE TABLE recording_identifier (unrecognized TEXT)");
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
        try(var input = ManagedRecordingIdentifierMigrationTest.class.getResourceAsStream(
            "/database/managed-recordings-format-6.sql"))
        { assertNotNull(input); frozen = input.readAllBytes(); }
        assertEquals("db63497b3bce0b5624877e05ecfd511a3254dcabee1811220ca376b5b09341fe",
            java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(frozen)));
        try(Connection connection = open(path); var statement = connection.createStatement())
        {
            for(String sql: new String(frozen, StandardCharsets.UTF_8).lines().toList()) statement.execute(sql);
            ManagedRecordingSchema.validate(connection, 6);
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
                        if(!table.equals("catalog_metadata") || column != 2) row.add(String.valueOf(rows.getObject(column)));
                    values.add(row.toString());
                }
            }
            Collections.sort(values);
            result.put(table, values);
        }
        result.putIfAbsent("recording_identifier", List.of());
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
