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

class ManagedRecordingPagingFrequencyMigrationTest
{
    @TempDir Path directory;

    @Test
    void populatedNinePreservesEveryFactAndSearchIndexBeforeAdmittingAdditionalAnalogNominals() throws Exception
    {
        Path catalog = fixture(directory.resolve("nine.sqlite"));
        Map<String,List<String>> before;
        try(Connection connection = open(catalog)) { before = contents(connection); }
        byte[] original = Files.readAllBytes(catalog);
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspectForStartup(catalog).state());
        assertArrayEquals(original, Files.readAllBytes(catalog));
        assertTrue(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
        try(Connection connection = open(catalog); var statement = connection.createStatement())
        {
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            assertEquals(before, contents(connection));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM recording_transcript_fts WHERE recording_transcript_fts MATCH 'verbatim'"));
            statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes,protocol,call_type,voice_type) " +
                "SELECT 9001,6000,7000,1000,'fixture/new.wav',64,protocol,call_type,voice_type FROM recording_call WHERE id=43");
            statement.executeUpdate("UPDATE catalog_metadata SET call_count=call_count+1,total_bytes=total_bytes+64 WHERE id=1");
            statement.executeUpdate("INSERT INTO recording_tone_sequence VALUES(9001,1,4,NULL,6000,6000,7000,1)");
            statement.executeUpdate("INSERT INTO recording_tone VALUES(9001,0,382300,NULL,NULL,0,200,990000)");
            integrity(connection);
        }
        try(var opened = new io.github.dsheirer.record.managed.ManagedRecordingCatalog(catalog, directory))
        {
            var calls = opened.search(io.github.dsheirer.record.managed.ManagedRecordingCatalog.SearchFilter.builder()
                .fromMs(6000L).toMs(8000L).build()).calls();
            var summary = (Map<?,?>)calls.stream().filter(call -> call.id() == 9001).findFirst().orElseThrow()
                .toMap().get("paging_tone_summary");
            assertEquals("analog_pcm_tone_v1", summary.get("method"));
            assertEquals(List.of(382.3), ((Map<?,?>)((List<?>)summary.get("tones")).getFirst()).get("frequencies_hz"));
        }
        assertFalse(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
    }

    @Test
    void failedTenUpgradeRollsBackPopulatedNineAndAllowsExactRetry() throws Exception
    {
        Path catalog = fixture(directory.resolve("retry.sqlite"));
        Map<String,List<String>> before;
        try(Connection connection = open(catalog)) { before = contents(connection); }
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, false,
            connection -> { throw new SQLException("Injected paging-frequency final validation failure"); },
            ManagedRecordingPagingFrequencyMigrationTest::open));
        try(Connection connection = open(catalog))
        {
            ManagedRecordingSchema.validate(connection, 9);
            assertEquals(before, contents(connection));
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

    private static Path fixture(Path path) throws Exception
    {
        byte[] frozen;
        try(var input = ManagedRecordingPagingFrequencyMigrationTest.class.getResourceAsStream(
            "/database/managed-recordings-format-9.sql"))
        { assertNotNull(input); frozen = input.readAllBytes(); }
        assertEquals("215c0bbb492485bbd3676994b6a342d2a43a02a08c815cafabd23d49ce21c20d",
            HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(frozen)));
        try(Connection connection = open(path); var statement = connection.createStatement())
        {
            for(String sql: new String(frozen, StandardCharsets.UTF_8).lines().toList()) statement.execute(sql);
            statement.execute("ANALYZE");
            ManagedRecordingSchema.validate(connection, 9);
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
