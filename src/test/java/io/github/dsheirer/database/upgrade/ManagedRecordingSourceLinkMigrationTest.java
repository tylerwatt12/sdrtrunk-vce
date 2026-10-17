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

class ManagedRecordingSourceLinkMigrationTest
{
    @TempDir Path directory;
    private static final String SOURCE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    @Test
    void frozenPopulatedTenPreservesEveryFactAndAddsOnlyEmptyExactLinks() throws Exception
    {
        Path catalog = fixture(directory.resolve("ten.sqlite"));
        Map<String,List<String>> before;
        try(Connection connection = open(catalog)) { before = contents(connection); }
        byte[] original = Files.readAllBytes(catalog);
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspectForStartup(catalog).state());
        assertThrows(SQLException.class, () -> new ManagedRecordingCatalog(catalog, directory));
        assertArrayEquals(original, Files.readAllBytes(catalog));
        var result = ManagedRecordingCatalogMigrator.migrate(catalog);
        assertTrue(result.migrated());
        assertNotNull(result.backup());
        try(Connection connection = open(catalog); var statement = connection.createStatement())
        {
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            assertEquals(before, contents(connection));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM recording_source"));
            assertEquals(9000, scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='recording_call'"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM recording_transcript_fts WHERE recording_transcript_fts MATCH 'verbatim'"));
            statement.executeUpdate("INSERT INTO recording_source VALUES(41,'" + SOURCE + "'),(42,'" + SOURCE + "')");
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO recording_source VALUES(43,'invalid')"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO recording_source VALUES(43,'" + SOURCE.toUpperCase() + "')"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO recording_source VALUES(9999,'" + SOURCE + "')"));
            statement.executeUpdate("DELETE FROM recording_call WHERE id=41");
            assertEquals(1, scalar(connection, "SELECT count(*) FROM recording_source"));
            integrity(connection);
        }
        try(Connection connection = open(result.backup()))
        { ManagedRecordingSchema.validate(connection, 10); assertEquals(before, contents(connection)); }
        Path retry = directory.resolve("retry-copy.sqlite");
        Files.copy(result.backup(), retry);
        assertTrue(ManagedRecordingCatalogMigrator.migrate(retry, false).migrated());
        try(Connection connection = open(retry))
        { ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION); assertEquals(before, contents(connection)); integrity(connection); }
        assertFalse(ManagedRecordingCatalogMigrator.migrate(retry, false).migrated());
    }

    @Test
    void failureRollsBackAllNewDdlAndAllowsExactRetryFromUnchangedTen() throws Exception
    {
        Path catalog = fixture(directory.resolve("rollback.sqlite"));
        Map<String,List<String>> before;
        try(Connection connection = open(catalog)) { before = contents(connection); }
        byte[] original = Files.readAllBytes(catalog);
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, false,
            connection -> { throw new SQLException("Injected source-link final validation failure"); },
            ManagedRecordingSourceLinkMigrationTest::open));
        assertArrayEquals(original, Files.readAllBytes(catalog));
        try(Connection connection = open(catalog))
        { ManagedRecordingSchema.validate(connection, 10); assertEquals(before, contents(connection)); integrity(connection); }
        assertTrue(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
        try(Connection connection = open(catalog))
        { ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION); assertEquals(before, contents(connection)); integrity(connection); }
    }

    private static Path fixture(Path path) throws Exception
    {
        byte[] frozen;
        try(var input = ManagedRecordingSourceLinkMigrationTest.class.getResourceAsStream(
            "/database/managed-recordings-format-10.sql"))
        { assertNotNull(input); frozen = input.readAllBytes(); }
        assertEquals("8bdc525dc83b1051001fe7404ea36fd9bc6105c00985ae66e8d5730111b936c4",
            HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(frozen)));
        try(Connection connection = open(path); var statement = connection.createStatement())
        {
            for(String sql: new String(frozen, StandardCharsets.UTF_8).lines().toList()) statement.execute(sql);
            statement.execute("ANALYZE");
            ManagedRecordingSchema.validate(connection, 10);
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
                        if(table.equals("catalog_metadata") && column == 2) continue;
                        Object value = rows.getObject(column);
                        row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : String.valueOf(value));
                    }
                    values.add(row.toString());
                }
            }
            Collections.sort(values); result.put(table, values);
        }
        result.putIfAbsent("recording_source", List.of());
        return result;
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    { try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
      { assertTrue(rows.next()); return rows.getLong(1); } }

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
