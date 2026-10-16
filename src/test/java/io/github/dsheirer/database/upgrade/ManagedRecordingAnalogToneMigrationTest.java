/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

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

class ManagedRecordingAnalogToneMigrationTest
{
    @TempDir Path directory;

    @Test
    void populatedEightPreservesEveryFactAndSearchIndexThenAdmitsAnalogToneMethod() throws Exception
    {
        Path catalog = fixture(directory.resolve("eight.sqlite"));
        Map<String,List<String>> before;
        try(Connection connection = open(catalog)) { before = contents(connection); }
        byte[] original = Files.readAllBytes(catalog);
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspectForStartup(catalog).state());
        assertArrayEquals(original, Files.readAllBytes(catalog));
        assertTrue(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
        try(Connection connection = open(catalog); var statement = connection.createStatement())
        {
            ManagedRecordingSchema.validate(connection, 9);
            assertEquals(before, contents(connection));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM recording_transcript_fts WHERE recording_transcript_fts MATCH 'verbatim'"));
            statement.executeUpdate("INSERT INTO recording_tone_sequence VALUES(43,1,4,NULL,4000,4000,5000,2)");
            statement.executeUpdate("INSERT INTO recording_tone VALUES(43,0,562500,NULL,NULL,0,200,990000),(43,1,697000,1209000,'DTMF_1',300,150,990000)");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE recording_tone_sequence SET source_code=2 WHERE call_id=43"));
            integrity(connection);
        }
        assertFalse(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
    }

    @Test
    void failedNineUpgradeRollsBackPopulatedEightAndAllowsExactRetry() throws Exception
    {
        Path catalog = fixture(directory.resolve("retry.sqlite"));
        Map<String,List<String>> before;
        try(Connection connection = open(catalog)) { before = contents(connection); }
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, false,
            connection -> { throw new SQLException("Injected analog-tone final validation failure"); },
            ManagedRecordingAnalogToneMigrationTest::open));
        try(Connection connection = open(catalog))
        {
            ManagedRecordingSchema.validate(connection, 8);
            assertEquals(before, contents(connection));
            integrity(connection);
        }
        assertTrue(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
        try(Connection connection = open(catalog))
        {
            ManagedRecordingSchema.validate(connection, 9);
            assertEquals(before, contents(connection));
            integrity(connection);
        }
    }

    private static Path fixture(Path path) throws Exception
    {
        byte[] frozen;
        try(var input = ManagedRecordingAnalogToneMigrationTest.class.getResourceAsStream(
            "/database/managed-recordings-format-8.sql"))
        { assertNotNull(input); frozen = input.readAllBytes(); }
        assertEquals("21fa8fb9d92302efe2e4b9c2cd0601b7a4f3ec8f0f37abc190f9a1ebfde3d70a",
            HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(frozen)));
        try(Connection connection = open(path); var statement = connection.createStatement())
        {
            for(String sql: new String(frozen, StandardCharsets.UTF_8).lines().toList()) statement.execute(sql);
            statement.execute("ANALYZE");
            ManagedRecordingSchema.validate(connection, 8);
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
